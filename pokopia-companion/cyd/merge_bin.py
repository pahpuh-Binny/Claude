# PlatformIO post-build step: merge bootloader, partition table and app into
# one image that flashes at address 0x0 (used by browser-based flashers).
import os

Import("env")  # noqa: F821  (provided by PlatformIO)


def merge(source, target, env):
    build = env.subst("$BUILD_DIR")
    platform = env.PioPlatform()
    boot_app0 = os.path.join(platform.get_package_dir("framework-arduinoespressif32"),
                             "tools", "partitions", "boot_app0.bin")
    esptool = os.path.join(platform.get_package_dir("tool-esptoolpy"), "esptool.py")
    out = os.path.join(build, "pokopia-companion-%s-factory.bin" % env["PIOENV"])
    cmd = ['"%s"' % env.subst("$PYTHONEXE"), '"%s"' % esptool, "--chip", "esp32", "merge_bin",
           "-o", '"%s"' % out, "--flash_mode", "dio", "--flash_freq", "40m", "--flash_size", "4MB",
           "0x1000", '"%s"' % os.path.join(build, "bootloader.bin"),
           "0x8000", '"%s"' % os.path.join(build, "partitions.bin"),
           "0xe000", '"%s"' % boot_app0,
           "0x10000", '"%s"' % os.path.join(build, "firmware.bin")]
    env.Execute(" ".join(cmd))


env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", merge)  # noqa: F821
