#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 .so 的动态依赖与未定义符号（用 section headers 定位 .dynsym/.dynstr）。"""
import struct
import sys
from pathlib import Path

SHT_DYNAMIC = 6
SHT_DYNSYM = 11
SHT_STRTAB = 3

DT_NULL = 0
DT_NEEDED = 1
DT_SONAME = 14


def parse_ehdr(data):
    (e_type, e_machine, e_version, e_entry, e_phoff, e_shoff,
     e_flags, e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum,
     e_shstrndx) = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    return dict(shoff=e_shoff, shentsize=e_shentsize, shnum=e_shnum,
                shstrndx=e_shstrndx)


def iter_shdrs(data, ehdr):
    for i in range(ehdr["shnum"]):
        off = ehdr["shoff"] + i * ehdr["shentsize"]
        (sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size,
         sh_link, sh_info, sh_addralign, sh_entsize) = struct.unpack_from(
            "<IIQQQQIIQQ", data, off)
        yield dict(idx=i, name=sh_name, type=sh_type, flags=sh_flags,
                   addr=sh_addr, offset=sh_offset, size=sh_size, link=sh_link,
                   info=sh_info, entsize=sh_entsize, hdr_off=off)


def analyze(path):
    data = path.read_bytes()
    ehdr = parse_ehdr(data)
    shdrs = list(iter_shdrs(data, ehdr))

    # shstrtab
    strtab_sec = shdrs[ehdr["shstrndx"]]
    shstr = data[strtab_sec["offset"]:strtab_sec["offset"] + strtab_sec["size"]]

    def sec_name(s):
        end = shstr.find(b"\x00", s["name"])
        return shstr[s["name"]:end].decode("utf-8", "replace")

    # 找 .dynamic / .dynsym / .dynstr
    dyn_sec = next((s for s in shdrs if s["type"] == SHT_DYNAMIC), None)
    dynsym_sec = next((s for s in shdrs if s["type"] == SHT_DYNSYM), None)
    # .dynstr：优先用 dynsym.sh_link 指向的 section，否则按名字找
    if dynsym_sec is not None:
        dynstr_sec = shdrs[dynsym_sec["link"]]
    else:
        dynstr_sec = next((s for s in shdrs if sec_name(s) == ".dynstr"), None)

    str_off = dynstr_sec["offset"]

    def read_str(val_off):
        start = str_off + val_off
        end = data.find(b"\x00", start)
        return data[start:end].decode("utf-8", "replace")

    # DT_NEEDED / DT_SONAME
    needed = []
    soname = None
    if dyn_sec:
        for j in range(dyn_sec["size"] // 16):
            d_tag, d_val = struct.unpack_from("<qQ", data, dyn_sec["offset"] + j * 16)
            if d_tag == DT_NULL:
                break
            if d_tag == DT_NEEDED:
                needed.append(read_str(d_val))
            elif d_tag == DT_SONAME:
                soname = read_str(d_val)

    # 遍历 .dynsym
    und = []
    defs = set()
    bind_of = {}  # name -> bind
    if dynsym_sec:
        ent = dynsym_sec["entsize"] or 24
        n = dynsym_sec["size"] // ent
        for k in range(n):
            so = dynsym_sec["offset"] + k * ent
            st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from(
                "<IBBHQQ", data, so)
            if st_name == 0:
                continue
            name = read_str(st_name)
            if not name:
                continue
            bind = st_info >> 4  # STB_LOCAL=0 GLOBAL=1 WEAK=2
            bind_of[name] = bind
            if st_shndx == 0:  # SHN_UNDEF
                und.append(name)
            else:
                defs.add(name)

    return dict(needed=needed, soname=soname, und=und, defs=defs, bind_of=bind_of)


def main():
    d = Path(sys.argv[1])
    for p in sorted(d.glob("*.so")):
        info = analyze(p)
        print(f"\n=== {p.name}  (SONAME={info['soname']}) ===")
        print(f"  DT_NEEDED ({len(info['needed'])}):")
        for n in info["needed"]:
            print(f"    - {n}")
        und = info["und"]
        ndk1 = [s for s in und if "__ndk1" in s]
        ggml_be = [s for s in und if "ggml_backend" in s and "_reg" in s]
        print(f"  UND 符号总数: {len(und)}；UND libc++(__ndk1): {len(ndk1)}")
        if ggml_be:
            print(f"  UND ggml_backend_*_reg ({len(ggml_be)}):")
            for s in ggml_be:
                print(f"    ! {s}")
        for sym in ("ggml_backend_vk_reg", "ggml_backend_opencl_reg",
                    "ggml_backend_cpu_reg", "ggml_backend_reg"):
            if sym in info["defs"]:
                print(f"  符号 {sym}: DEF（本库定义）")
            elif sym in und:
                b = info["bind_of"].get(sym, -1)
                btag = {0: "LOCAL", 1: "GLOBAL(强)", 2: "WEAK(弱)"}.get(b, str(b))
                print(f"  符号 {sym}: UND [{btag}]")
        # 是否依赖 libc++_shared
        if "libc++_shared.so" in info["needed"]:
            print(f"  >>> 显式依赖 libc++_shared.so")


if __name__ == "__main__":
    main()
