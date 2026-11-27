"""Read-only Frida handshake over an ADB forward owned by the desktop app."""

import json
import sys

import frida


def main():
    device = frida.get_device_manager().add_remote_device(sys.argv[1])
    parameters = device.query_system_parameters()
    arch = parameters.get("arch", "")
    result = {
        "arch": {"ia32": "x86", "x64": "x86_64", "aarch64": "arm64"}.get(arch, arch),
    }
    if len(sys.argv) > 2:
        package = sys.argv[2]
        result["appRunning"] = any(
            app.identifier == package and app.pid > 0
            for app in device.enumerate_applications()
        )
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"error": type(error).__name__, "detail": str(error)}))
