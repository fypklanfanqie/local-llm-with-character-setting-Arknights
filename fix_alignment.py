#!/usr/bin/env python3
"""
Fix ELF LOAD segment alignment from 4 KB (0x1000) to 16 KB (0x4000).

Android 16+ requires all native libraries to have 16 KB page alignment.
The llama.cpp prebuilt .so files were compiled with 4 KB alignment and
will be rejected by the dynamic linker on Android 16 devices.

This script patches the p_align field of LOAD program headers.
"""

import struct
import sys
from pathlib import Path

TARGET_ALIGN = 0x4000  # 16 KB


def patch_alignment(data: bytearray) -> int:
    """Patch all LOAD segment alignments. Returns number of segments patched."""
    if data[:4] != b"\x7fELF":
        print("  Not an ELF file")
        return 0

    ei_class = data[4]  # 2 = 64-bit
    ei_data = data[5]   # 1 = little-endian, 2 = big-endian

    if ei_class != 2:
        print("  Not 64-bit, skipping")
        return 0

    endian = "<" if ei_data == 1 else ">"

    # ELF64 header: e_phoff at offset 0x20 (8 bytes), e_phentsize at 0x36 (2 bytes), e_phnum at 0x38 (2 bytes)
    e_phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
    e_phnum = struct.unpack_from(endian + "H", data, 0x38)[0]

    if e_phoff == 0 or e_phnum == 0:
        print("  No program headers")
        return 0

    patched = 0
    PT_LOAD = 1

    for i in range(e_phnum):
        phdr_off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, phdr_off)[0]

        if p_type == PT_LOAD:
            # p_align is at offset 48 (0x30) in Elf64_Phdr
            align_off = phdr_off + 0x30
            current_align = struct.unpack_from(endian + "Q", data, align_off)[0]

            if current_align != 0x1000:
                print(f"  LOAD segment {i}: align=0x{current_align:x} (already not 4KB, skipping)")
                continue

            struct.pack_into(endian + "Q", data, align_off, TARGET_ALIGN)
            patched += 1
            print(f"  LOAD segment {i}: 0x1000 → 0x{TARGET_ALIGN:x}")

    return patched


def main():
    jni_dir = Path(__file__).parent / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
    targets = ["libggml-base.so", "libggml-cpu.so", "libggml.so",
               "libllama.so", "libllama-common.so", "libllama_jni.so"]

    for name in targets:
        path = jni_dir / name
        print(f"Patching {name}...")
        if not path.exists():
            print(f"  NOT FOUND, skipping")
            continue
        data = bytearray(path.read_bytes())
        n = patch_alignment(data)
        if n > 0:
            path.write_bytes(bytes(data))
            print(f"  OK: {n} segment(s) patched")
        else:
            print(f"  No changes needed")


if __name__ == "__main__":
    main()
