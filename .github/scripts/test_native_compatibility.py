"""检查发行产物的架构、静态链接要求与 glibc 兼容上限。"""

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("compatibility", Path(__file__).with_name("check-native-compatibility.py"))
compatibility = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compatibility)


class NativeCompatibilityTest(unittest.TestCase):
    # 完全静态的普通 ELF 和静态 PIE 都不需要动态加载器或共享库。
    def test_static_x64_accepts_absent_dynamic_section_and_static_pie(self):
        for dynamic in ("There is no dynamic section in this file.", " 0x6ffffffb (FLAGS_1) Flags: PIE"):
            result = compatibility.validate("x64", 62, " LOAD 0x000000", dynamic, "No version information found")
            self.assertEqual(result["libraries"], [])
            self.assertIsNone(result["maximumGlibc"])

    # 即使只残留加载器、共享库或 glibc 符号之一，也不能当成完全静态产物发布。
    def test_x64_rejects_each_kind_of_dynamic_dependency(self):
        for segments, dynamic, versions in ((" INTERP 0x000318", "", ""),
                                             ("", " (NEEDED) Shared library: [libz.so.1]", ""),
                                             ("", "", "Name: GLIBC_2.17")):
            with self.subTest(segments=segments, dynamic=dynamic, versions=versions):
                with self.assertRaisesRegex(ValueError, "fully static"):
                    compatibility.validate("x64", 62, segments, dynamic, versions)

    # ARM64 按版本数字比较，接受旧符号及 2.28 边界，拒绝任何更新或私有 glibc ABI。
    def test_arm64_enforces_glibc_baseline(self):
        for versions, accepted in (("Name: GLIBC_2.9\nName: GLIBC_2.28", True),
                                   ("Name: GLIBC_2.17", True),
                                   ("Name: GLIBC_2.28\nName: GLIBC_2.29", False),
                                   ("Name: GLIBC_2.17\nName: GLIBC_2.34", False),
                                   ("Name: GLIBC_2.28\nName: GLIBC_PRIVATE", False),
                                   ("Name: GLIBC_2.28\nName: GLIBC_ABI_DT_RELR", False),
                                   ("No version information found", False)):
            with self.subTest(versions=versions):
                if accepted:
                    compatibility.validate("arm64", 183, " INTERP 0x000238", "", versions)
                else:
                    with self.assertRaisesRegex(ValueError, "at most 2.28"):
                        compatibility.validate("arm64", 183, " INTERP 0x000238", "", versions)

    # ARM64 允许 glibc 拆分库，但 zlib 或其他外部库必须已经静态链接。
    def test_arm64_allows_only_glibc_shared_libraries(self):
        dynamic = "\n".join(f" (NEEDED) Shared library: [{name}]"
                            for name in ("libc.so.6", "libpthread.so.0", "libdl.so.2", "ld-linux-aarch64.so.1"))
        compatibility.validate("arm64", 183, " INTERP 0x000238", dynamic, "Name: GLIBC_2.28")
        for library in ("libz.so.1", "libstdc++.so.6", "libgcc_s.so.1"):
            with self.subTest(library=library):
                with self.assertRaisesRegex(ValueError, "only glibc"):
                    compatibility.validate("arm64", 183, " INTERP 0x000238",
                                           dynamic + f"\n (NEEDED) Shared library: [{library}]", "Name: GLIBC_2.28")

    # 产物架构与发布名称不符时立即失败，防止把 x64 文件打进 ARM64 压缩包。
    def test_rejects_wrong_architecture(self):
        with self.assertRaisesRegex(ValueError, "Expected arm64 ELF"):
            compatibility.validate("arm64", 62, "", "", "")


if __name__ == "__main__":
    unittest.main()
