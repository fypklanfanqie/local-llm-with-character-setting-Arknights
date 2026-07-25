#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
诊断并修补 arm64-v8a/*.so 的 ELF .dynamic section 头与 PT_DYNAMIC 不一致问题。

问题表现（Android linker 报错）：
    .dynamic section has invalid size: 0x1d0 (expected to match PT_DYNAMIC filesz 0x1f0)

成因：GNU strip 删除了部分 dynamic 表项，更新了 section header 的 sh_size，
但未同步 PT_DYNAMIC 程序头的 p_filesz，二者出现 0x20（2 个 Elf64_Dyn）差值。
Android bionic linker（API 26+）对此做严格校验，直接拒绝加载。

修法：把 .dynamic section header 的 sh_size 改成与 PT_DYNAMIC.p_filesz 一致。
这是外科手术式二进制修补，不影响运行时（运行时只用程序头，section 头仅调试用）。
"""
import struct
import sys
from pathlib import Path

PT_DYNAMIC = 2
SHT_DYNAMIC = 6

# ELF64 结构尺寸
ELF64_EHDR = 64
ELF64_PHDR = 56
ELF64_SHDR = 64


def parse_ehdr(data):
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    ei_class = data[4]  # 1=32bit, 2=64bit
    if ei_class != 2:
        raise ValueError("仅支持 ELF64")
    (e_type, e_machine, e_version, e_entry, e_phoff, e_shoff,
     e_flags, e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum,
     e_shstrndx) = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    return dict(phoff=e_phoff, shoff=e_shoff, phentsize=e_phentsize,
                phnum=e_phnum, shentsize=e_shentsize, shnum=e_shnum,
                shstrndx=e_shstrndx)


def iter_phdrs(data, ehdr):
    for i in range(ehdr["phnum"]):
        off = ehdr["phoff"] + i * ehdr["phentsize"]
        (p_type, p_flags, p_offset, p_vaddr, p_paddr,
         p_filesz, p_memsz, p_align) = struct.unpack_from("<IIQQQQQQ", data, off)
        yield dict(type=p_type, flags=p_flags, offset=p_offset, vaddr=p_vaddr,
                   filesz=p_filesz, memsz=p_memsz, align=p_align, hdr_off=off)


def iter_shdrs(data, ehdr):
    for i in range(ehdr["shnum"]):
        off = ehdr["shoff"] + i * ehdr["shentsize"]
        (sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size,
         sh_link, sh_info, sh_addralign, sh_entsize) = struct.unpack_from(
            "<IIQQQQIIQQ", data, off)
        yield dict(idx=i, name=sh_name, type=sh_type, flags=sh_flags,
                   addr=sh_addr, offset=sh_offset, size=sh_size, link=sh_link,
                   info=sh_info, hdr_off=off)


def get_shstrtab(data, ehdr):
    shdrs = list(iter_shdrs(data, ehdr))
    strtab = shdrs[ehdr["shstrndx"]]
    start = strtab["offset"]
    end = start + strtab["size"]
    return data[start:end]


def section_name(strtab, name_off):
    end = strtab.find(b"\x00", name_off)
    return strtab[name_off:end].decode("utf-8", "replace")


def analyze(path):
    data = path.read_bytes()
    ehdr = parse_ehdr(data)
    phdrs = list(iter_phdrs(data, ehdr))
    shdrs = list(iter_shdrs(data, ehdr))
    strtab = get_shstrtab(data, ehdr)

    pt_dyn = next((p for p in phdrs if p["type"] == PT_DYNAMIC), None)
    sh_dyn = next((s for s in shdrs if s["type"] == SHT_DYNAMIC), None)

    if pt_dyn is None or sh_dyn is None:
        return dict(ok=True, note="无 PT_DYNAMIC/.dynamic（可能已 stripped），跳过",
                    path=str(path))

    name = section_name(strtab, sh_dyn["name"])
    p_filesz = pt_dyn["filesz"]
    sh_size = sh_dyn["size"]
    mismatch = p_filesz != sh_size
    return dict(
        ok=not mismatch,
        path=str(path),
        section=name,
        p_filesz=p_filesz,
        sh_size=sh_size,
        diff=p_filesz - sh_size,
        sh_hdr_off=sh_dyn["hdr_off"],  # sh_size 字段位于 hdr_off + 32
        pt_hdr_off=pt_dyn["hdr_off"],
        p_offset=pt_dyn["offset"],
        sh_offset=sh_dyn["offset"],
    )


def fix(path, info):
    """把 .dynamic 的 sh_size 改为 PT_DYNAMIC.p_filesz。"""
    data = bytearray(path.read_bytes())
    # sh_size 在 Elf64_Shdr 偏移 32 处
    sh_size_off = info["sh_hdr_off"] + 32
    struct.pack_into("<Q", data, sh_size_off, info["p_filesz"])
    # 校验：sh_offset 应与 p_offset 一致（同一张表）
    if info["sh_offset"] != info["p_offset"]:
        print(f"  ⚠ 警告：{path.name} 的 .dynamic sh_offset({info['sh_offset']:#x}) "
              f"与 PT_DYNAMIC p_offset({info['p_offset']:#x}) 不一致，仍按 p_filesz 修补 sh_size")
    path.write_bytes(bytes(data))


def main():
    if len(sys.argv) < 2:
        print("用法: python fix_elf_dynamic.py <jniLibs/arm64-v8a 目录> [--fix]")
        sys.exit(1)
    target_dir = Path(sys.argv[1])
    do_fix = "--fix" in sys.argv

    so_files = sorted(target_dir.glob("*.so"))
    if not so_files:
        print(f"目录下无 .so 文件: {target_dir}")
        sys.exit(1)

    print(f"模式: {'修补 (--fix)' if do_fix else '仅诊断'}")
    print(f"目录: {target_dir}\n")

    need_fix = []
    for p in so_files:
        try:
            info = analyze(p)
        except Exception as e:
            print(f"  ✗ {p.name}: 解析失败 - {e}")
            continue
        if "note" in info:
            print(f"  - {p.name}: {info['note']}")
            continue
        if info["ok"]:
            print(f"  ✓ {p.name}: 一致 (sh_size == p_filesz == {info['p_filesz']:#x})")
        else:
            print(f"  ✗ {p.name}: 不一致 sh_size={info['sh_size']:#x} "
                  f"p_filesz={info['p_filesz']:#x} diff={info['diff']:#x}")
            need_fix.append((p, info))

    if not need_fix:
        print("\n所有 .so 的 .dynamic 头均一致，无需修补。")
        return

    print(f"\n需修补: {len(need_fix)} 个文件")
    if not do_fix:
        print("加 --fix 参数执行修补。")
        return

    for p, info in need_fix:
        fix(p, info)
        print(f"  ✓ 已修补 {p.name}: sh_size {info['sh_size']:#x} -> {info['p_filesz']:#x}")
    print("\n修补完成，请重新构建 APK 后部署验证。")


if __name__ == "__main__":
    main()
