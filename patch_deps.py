#!/usr/bin/env python3
"""
Patch ELF .dynamic section: remove NEEDED entries for missing GPU backends.

The key fix vs the old script: instead of nulling out entries (which truncates
the array because DT_NULL is the terminator), we COMPACT the array — read all
entries, filter out the unwanted ones, and write back with proper termination.

Also handles recovery from the previous bad patch (which left DT_NULL holes).
"""

import struct
from pathlib import Path

MISSING = {b"libggml-vulkan.so", b"libggml-opencl.so"}

DT_NULL = 0
DT_NEEDED = 1
ELFCLASS64 = 2
ELFDATA2LSB = 1


def find_section(data: bytes, name: bytes) -> tuple[int, int, int] | None:
    """Returns (offset, size, entry_count) for a section, or None."""
    if data[:4] != b"\x7fELF" or data[4] != ELFCLASS64:
        return None
    endian = "<" if data[5] == ELFDATA2LSB else ">"

    e_shoff = struct.unpack_from(endian + "Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from(endian + "H", data, 0x3A)[0]
    e_shnum = struct.unpack_from(endian + "H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from(endian + "H", data, 0x3E)[0]

    shstr_hdr = e_shoff + e_shstrndx * e_shentsize
    shstr_off = struct.unpack_from(endian + "Q", data, shstr_hdr + 0x18)[0]

    for i in range(e_shnum):
        hdr = e_shoff + i * e_shentsize
        sh_name = struct.unpack_from(endian + "I", data, hdr)[0]
        sec_name = data[shstr_off + sh_name:].split(b"\x00")[0]
        if sec_name == name:
            off = struct.unpack_from(endian + "Q", data, hdr + 0x18)[0]
            size = struct.unpack_from(endian + "Q", data, hdr + 0x20)[0]
            count = size // 16  # Elf64_Dyn = 16 bytes
            return (off, size, count)
    return None


def patch_elf(path: Path) -> bool:
    data = bytearray(path.read_bytes())
    endian = "<" if data[5] == ELFDATA2LSB else ">"

    r = find_section(bytes(data), b".dynamic")
    if r is None:
        print(f"  No .dynamic section, skipping")
        return False
    dyn_off, dyn_size, dyn_count = r

    # Read all dynamic entries
    entries = []
    for i in range(dyn_count):
        pos = dyn_off + i * 16
        d_tag = struct.unpack_from(endian + "q", data, pos)[0]
        d_val = struct.unpack_from(endian + "q", data, pos + 8)[0]
        entries.append((d_tag, d_val))

    # Find .dynstr for string lookup
    ds = find_section(bytes(data), b".dynstr")
    dynstr_off = ds[0] if ds else 0

    kept = []
    removed = 0
    for tag, val in entries:
        if tag == DT_NULL:
            # Skip stray NULLs (from previous bad patch) — they'll be re-added at the end
            if val == 0 and len(kept) < len(entries) - 1:
                # This might be a stray NULL; skip it, but only if not the real terminator
                # The real terminator is the LAST non-zero entry followed by NULL
                continue
        if tag == DT_NEEDED:
            st = bytes(data[dynstr_off + val:dynstr_off + val + 128].split(b"\x00")[0])
            if st in MISSING:
                print(f"  Removing NEEDED: {st.decode()}")
                removed += 1
                continue
        kept.append((tag, val))

    if removed == 0:
        print(f"  No missing deps found, compacting only")

    # Always compact: remove stray NULLs from previous bad patch

    # Write back compacted entries (no gaps, no stray NULLs)
    for i, (tag, val) in enumerate(kept):
        pos = dyn_off + i * 16
        struct.pack_into(endian + "q", data, pos, tag)
        struct.pack_into(endian + "q", data, pos + 8, val)

    # Add final DT_NULL terminator
    final_pos = dyn_off + len(kept) * 16
    struct.pack_into(endian + "q", data, final_pos, DT_NULL)
    struct.pack_into(endian + "q", data, final_pos + 8, 0)

    # Zero out remaining entries in the section to avoid stale data
    new_count = len(kept) + 1
    for i in range(new_count, dyn_count):
        pos = dyn_off + i * 16
        struct.pack_into(endian + "q", data, pos, 0)
        struct.pack_into(endian + "q", data, pos + 8, 0)

    # Update .dynamic section size
    r2 = find_section(bytes(data), b".dynamic")
    # Actually, sh_size in the section header needs updating. Find the section
    # header entry for .dynamic and update its sh_size.
    e_shoff = struct.unpack_from(endian + "Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from(endian + "H", data, 0x3A)[0]
    e_shnum = struct.unpack_from(endian + "H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from(endian + "H", data, 0x3E)[0]
    shstr_hdr = e_shoff + e_shstrndx * e_shentsize
    shstr_off = struct.unpack_from(endian + "Q", data, shstr_hdr + 0x18)[0]

    for i in range(e_shnum):
        hdr = e_shoff + i * e_shentsize
        sh_name = struct.unpack_from(endian + "I", data, hdr)[0]
        sec_name = data[shstr_off + sh_name:].split(b"\x00")[0]
        if sec_name == b".dynamic":
            new_size = new_count * 16
            struct.pack_into(endian + "Q", data, hdr + 0x20, new_size)
            print(f"  Updated .dynamic size: {dyn_count * 16} -> {new_size}")
            break

    path.write_bytes(bytes(data))
    print(f"  OK: removed {removed} entries, kept {len(kept)}")
    return True


def main():
    jni = Path(__file__).parent / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
    for name in ["libggml.so", "libllama.so", "libllama-common.so"]:
        p = jni / name
        print(f"Patching {name}...")
        if p.exists():
            patch_elf(p)
        else:
            print(f"  NOT FOUND")


if __name__ == "__main__":
    main()
