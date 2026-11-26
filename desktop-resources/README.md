Native application resources are loaded from `common/` by the Compose packaging task.

`common/tools/` contains optional, locally prepared binaries and Python packages. It is excluded from Git. Run `prepare-tools.ps1` to populate it. Builds and HAR analysis work without this folder.

The source repository does not vendor their binary distributions. See the third-party notices before distributing a binary bundle.
