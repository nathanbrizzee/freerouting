#!/usr/bin/env python3
r"""Check the tools a Freerouting checkout needs, and install what is missing.
Windows 11 (winget) and Ubuntu / Debian (apt); macOS (Homebrew) on a best-effort basis.
Standard library only; the one prerequisite is Python 3.8+.

    python scripts/setup/setup.py                # check, install what is missing, re-check, build once
    python scripts/setup/setup.py --check-only   # print [OK] / [MISSING], change nothing
    python scripts/setup/setup.py --dry-run      # print what would be installed and run, change nothing
    python scripts/setup/setup.py --yes          # unattended (no confirmation before installing)
    python scripts/setup/setup.py --with-kicad   # also KiCad (only the DSN export scripts need it)
    python scripts/setup/setup.py --no-build     # skip the Gradle wrapper warm-up

Windows: `py scripts\setup\setup.py`. Linux: `python3 scripts/setup/setup.py` (apt installs use sudo, once).

What it covers:
  * git, and the GitHub CLI (`gh`, for pull requests and issues).
  * A JDK 25 (Eclipse Temurin). Gradle 9 itself is not installed: the Gradle wrapper (`./gradlew`) downloads
    it, and the build's toolchain setting needs a JDK 25 to compile with. On Ubuntu the JDK comes from apt when
    the release has `openjdk-25-jdk`, else from the Temurin tarball (Adoptium), unpacked under
    ~/.local/share/jdks/ with a stable `temurin-25` link.
  * Python packages: pre-commit (the repository hooks) and requests (scripts/i18n). The hooks are installed
    into the checkout with `pre-commit install`.
  * One warm-up of the Gradle wrapper (`gradlew --version`), so the first real build does not stall on downloads.
  * Optional with --with-kicad: KiCad (scripts/benchmark/overhaul/export_dsn.py imports its pcbnew module). On
    Ubuntu it comes from the KiCad project's PPA (ppa:kicad/kicad-10.0-releases), because Ubuntu's own
    repositories carry an older series; the system `python3` then imports pcbnew.

It does not install an IDE, Docker, Node or any Claude tooling, and it never edits shell profiles or the
registry: where JAVA_HOME matters it prints the line to add.

Exit code = number of required items still missing after the run.
"""
import argparse
import glob
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

JAVA_MAJOR = 25
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))

# (label, executables, winget id, apt packages, brew formula or --cask name)
TOOLS = [
    ("git", ("git",), "Git.Git", ["git"], "git"),
    ("GitHub CLI (gh)", ("gh",), "GitHub.cli", ["gh"], "gh"),
]
KICAD = ("KiCad", ("kicad-cli", "kicad"), "KiCad.KiCad", ["kicad"], "--cask kicad")
PY_PACKAGES = [("pre-commit", "pre_commit"), ("requests", "requests")]

KICAD_PPA = "ppa:kicad/kicad-10.0-releases"

STATE = {"platform": None, "dry": False, "yes": False}


def plat():
    return STATE["platform"]


def is_win():
    return plat() == "windows"


def is_linux():
    return plat() == "linux"


def is_mac():
    return plat() == "macos"


def is_ubuntu():
    """Ubuntu (not plain Debian), by /etc/os-release; a --dry-run on another host assumes Ubuntu."""
    if STATE["dry"] and not sys.platform.startswith("linux"):
        return True
    try:
        with open("/etc/os-release") as f:
            return re.search(r"^ID(_LIKE)?=.*ubuntu", f.read(), re.M) is not None
    except OSError:
        return False


def detect_platform():
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    return "linux"


def run(cmd, **kw):
    print("   $", " ".join(cmd))
    if STATE["dry"]:
        return True
    return subprocess.run(cmd, **kw).returncode == 0


def sudo(cmd):
    """apt needs root; a root shell (a container, say) has no sudo."""
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        return cmd
    return ["sudo"] + cmd


def confirm(what):
    if STATE["yes"] or STATE["dry"]:
        return True
    try:
        return input(f"Install {what}? [Y/n] ").strip().lower() in ("", "y", "yes")
    except EOFError:
        return True


def winget_install(winget_id):
    return run(["winget", "install", "--id", winget_id, "-e", "--silent",
                "--accept-package-agreements", "--accept-source-agreements"])


def os_install(label, winget_id, apt, brew):
    """Install one tool with the platform's package manager. Returns whether the command succeeded."""
    if not confirm(label):
        return False
    if is_win():
        return (STATE["dry"] or shutil.which("winget") is not None) and winget_install(winget_id)
    if is_mac():
        return (STATE["dry"] or shutil.which("brew") is not None) and run(["brew", "install"] + brew.split())
    if is_linux():
        if not STATE["dry"] and shutil.which("apt-get") is None:
            return False
        if label.startswith("KiCad") and is_ubuntu():
            # Ubuntu's own repositories carry an older KiCad; the project's PPA has the 10.x series
            # (noble, 26.04 and 26.10 are published there).
            run(sudo(["apt-get", "install", "-y", "software-properties-common"]))
            run(sudo(["add-apt-repository", "-y", KICAD_PPA]))
        run(sudo(["apt-get", "update"]))
        return run(sudo(["apt-get", "install", "-y"] + apt))
    return False


def program_dirs():
    """New shells see freshly installed tools; this process does not. Look where the installers put them."""
    dirs = []
    if is_win():
        pf = [os.environ.get("ProgramFiles", r"C:\Program Files"),
              os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")]
        local = os.environ.get("LOCALAPPDATA", "")
        for base in pf:
            dirs += [os.path.join(base, "Git", "cmd"), os.path.join(base, "GitHub CLI")]
            dirs += glob.glob(os.path.join(base, "KiCad", "*", "bin"))
        dirs += [os.path.join(local, "Programs", "Git", "cmd"), os.path.join(local, "GitHubCLI")]
    if is_mac():
        dirs += ["/opt/homebrew/bin", "/usr/local/bin"]
    return dirs


def find(names):
    for name in names:
        path = shutil.which(name)
        if path:
            return path
    for d in program_dirs():
        for name in names:
            for ext in ("", ".exe", ".cmd"):
                p = os.path.join(d, name + ext)
                if os.path.isfile(p):
                    return p
    return None


# ----------------------------------------------------------------------------------------------- JDK


def java_dirs():
    """Directories that hold a JDK (bin/java next to bin/javac), best candidates first."""
    cands = []
    for var in ("JAVA_HOME", "FREEROUTING_JAVA_HOME"):
        if os.environ.get(var):
            cands.append(os.environ[var])
    if is_win():
        for base in (os.environ.get("ProgramFiles", r"C:\Program Files"),):
            cands += sorted(glob.glob(os.path.join(base, "Eclipse Adoptium", "jdk-25*")), reverse=True)
            cands += sorted(glob.glob(os.path.join(base, "Java", "jdk-25*")), reverse=True)
            cands += sorted(glob.glob(os.path.join(base, "Microsoft", "jdk-25*")), reverse=True)
    elif is_linux():
        cands += sorted(glob.glob("/usr/lib/jvm/*25*"), reverse=True)
        cands += sorted(glob.glob(os.path.expanduser("~/.local/share/jdks/*25*")), reverse=True)
    elif is_mac():
        cands += sorted(glob.glob("/Library/Java/JavaVirtualMachines/*25*/Contents/Home"), reverse=True)
        cands += ["/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"]
    path_java = shutil.which("java")
    if path_java:
        cands.append(os.path.dirname(os.path.dirname(os.path.realpath(path_java))))
    return cands


def java_major(home):
    """The major version of the JDK at home, or 0 when it is not a working JDK."""
    exe = ".exe" if is_win() else ""
    java = os.path.join(home, "bin", "java" + exe)
    javac = os.path.join(home, "bin", "javac" + exe)
    if not (os.path.isfile(java) and os.path.isfile(javac)):
        return 0
    try:
        out = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return 0
    m = re.search(r'version "(\d+)', out.stdout + out.stderr)
    return int(m.group(1)) if m else 0


def find_jdk():
    for home in java_dirs():
        if java_major(home) >= JAVA_MAJOR:
            return home
    return None


def adoptium_arch():
    machine = platform.machine().lower()
    return "aarch64" if machine in ("aarch64", "arm64") else "x64"


def apt_has_package(name):
    r = subprocess.run(["apt-cache", "policy", name], capture_output=True, text=True)
    return r.returncode == 0 and "Candidate:" in r.stdout and "Candidate: (none)" not in r.stdout


def install_jdk_linux_tarball():
    """Temurin from Adoptium, unpacked under ~/.local/share/jdks with a stable temurin-25 link."""
    url = (f"https://api.adoptium.net/v3/binary/latest/{JAVA_MAJOR}/ga/linux/{adoptium_arch()}"
           "/jdk/hotspot/normal/eclipse")
    dest = os.path.expanduser("~/.local/share/jdks")
    print(f"   download {url}\n   unpack into {dest}")
    if STATE["dry"]:
        return True
    os.makedirs(dest, exist_ok=True)
    try:
        tmp = os.path.join(tempfile.mkdtemp(), "temurin.tar.gz")
        urllib.request.urlretrieve(url, tmp)
        with tarfile.open(tmp) as tar:
            top = tar.getnames()[0].split("/")[0]
            if hasattr(tarfile, "data_filter"):
                tar.extractall(dest, filter="data")
            else:
                tar.extractall(dest)
    except (OSError, tarfile.TarError) as e:
        print("   JDK download failed:", e)
        return False
    link = os.path.join(dest, f"temurin-{JAVA_MAJOR}")
    if os.path.islink(link):
        os.remove(link)
    if not os.path.exists(link):
        os.symlink(os.path.join(dest, top), link)
    return True


def ensure_jdk(check_only):
    home = find_jdk()
    if home or check_only:
        return home
    print(f"[MISSING] JDK {JAVA_MAJOR}: installing")
    if is_win():
        os_install(f"JDK {JAVA_MAJOR} (Temurin)", f"EclipseAdoptium.Temurin.{JAVA_MAJOR}.JDK", [], "")
    elif is_mac():
        os_install(f"JDK {JAVA_MAJOR} (Temurin)", "", [], f"--cask temurin@{JAVA_MAJOR}")
    elif is_linux():
        if confirm(f"JDK {JAVA_MAJOR}"):
            if STATE["dry"] or (shutil.which("apt-get") and apt_has_package(f"openjdk-{JAVA_MAJOR}-jdk")):
                run(sudo(["apt-get", "update"]))
                run(sudo(["apt-get", "install", "-y", f"openjdk-{JAVA_MAJOR}-jdk"]))
                if STATE["dry"]:
                    print(f"   (when apt has no openjdk-{JAVA_MAJOR}-jdk, the Temurin tarball is used instead)")
            else:
                install_jdk_linux_tarball()
    return find_jdk()


# ----------------------------------------------------------------------------------------------- Python


def have_py(module):
    return subprocess.run([sys.executable, "-c", "import " + module], capture_output=True).returncode == 0


def pip_install(name):
    """pip into the user site. Ubuntu 23.04+ and Debian 12+ mark the system Python 'externally managed'
    (PEP 668) and refuse; then retry with --break-system-packages, which with --user only writes ~/.local."""
    cmd = [sys.executable, "-m", "pip", "install", "--user", name]
    if os.environ.get("VIRTUAL_ENV") or is_win():
        cmd.remove("--user")
    if run(cmd):
        return True
    return is_linux() and run(cmd + ["--break-system-packages"])


# ----------------------------------------------------------------------------------------------- main


def gradle_warmup(jdk_home):
    wrapper = os.path.join(REPO, "gradlew.bat" if is_win() else "gradlew")
    if not os.path.isfile(wrapper):
        print("[--] Gradle wrapper not found; run this from a Freerouting checkout")
        return False
    env = dict(os.environ)
    env["JAVA_HOME"] = jdk_home
    print("   warming up the Gradle wrapper (downloads Gradle the first time)")
    if STATE["dry"]:
        print("   $", wrapper, "--version")
        return True
    if not is_win():
        os.chmod(wrapper, os.stat(wrapper).st_mode | 0o111)
    return subprocess.run([wrapper, "--version"], cwd=REPO, env=env).returncode == 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check-only", action="store_true", help="report only, change nothing")
    ap.add_argument("--dry-run", action="store_true", help="print the install commands, change nothing")
    ap.add_argument("--yes", action="store_true", help="do not ask before installing")
    ap.add_argument("--with-kicad", action="store_true", help="also install KiCad")
    ap.add_argument("--no-build", action="store_true", help="skip the Gradle wrapper warm-up")
    ap.add_argument("--platform", choices=("windows", "linux", "macos"),
                    help="pretend to be this platform (use with --dry-run)")
    a = ap.parse_args()
    if sys.version_info < (3, 8):
        sys.exit("Python 3.8+ required")
    STATE.update(platform=a.platform or detect_platform(), dry=a.dry_run, yes=a.yes)
    check_only = a.check_only
    print(f"Freerouting setup on {plat()} (checkout: {REPO})")

    missing = 0
    tools = list(TOOLS) + ([KICAD] if a.with_kicad else [])
    for label, exes, winget_id, apt, brew in tools:
        path = find(exes)
        if not path and not check_only:
            print(f"[MISSING] {label}: installing")
            if os_install(label, winget_id, apt, brew):
                path = find(exes)
        if path:
            print(f"[OK] {label}: {path}")
        elif a.dry_run:
            print(f"[DRY] {label}: would be installed")
        else:
            optional = label.startswith("KiCad")
            missing += 0 if optional else 1
            print(f"[MISSING] {label}  (winget install {winget_id} / apt install {' '.join(apt)})")

    jdk = ensure_jdk(check_only)
    if jdk:
        print(f"[OK] JDK {JAVA_MAJOR}: {jdk}")
    elif a.dry_run:
        print(f"[DRY] JDK {JAVA_MAJOR}: would be installed")
    else:
        missing += 1
        print(f"[MISSING] JDK {JAVA_MAJOR} (Temurin: https://adoptium.net/temurin/releases/?version={JAVA_MAJOR})")

    for pip_name, module in PY_PACKAGES:
        if not have_py(module) and not check_only:
            print(f"[MISSING] python package {pip_name}: installing")
            pip_install(pip_name)
        if have_py(module):
            print(f"[OK] python package {pip_name}")
        elif a.dry_run:
            print(f"[DRY] python package {pip_name}: would be installed")
        else:
            missing += 1
            print(f"[MISSING] python package {pip_name}  (pip install {pip_name})")

    if os.path.isdir(os.path.join(REPO, ".git")) and have_py("pre_commit") and not check_only:
        hook = os.path.join(REPO, ".git", "hooks", "pre-commit")
        if os.path.isfile(hook):
            print("[OK] pre-commit hooks already installed")
        else:
            run([sys.executable, "-m", "pre_commit", "install"], cwd=REPO)

    if jdk and not check_only and not a.no_build:
        if gradle_warmup(jdk):
            print("[OK] Gradle wrapper")
        else:
            missing += 1
            print("[MISSING] Gradle wrapper did not start (network? JAVA_HOME?)")

    if jdk and (os.environ.get("JAVA_HOME") or "") != jdk:
        if is_win():
            print(f'\nnote: set JAVA_HOME for this checkout: setx JAVA_HOME "{jdk}"  (a new terminal is needed)')
        else:
            print(f'\nnote: add to your shell profile:  export JAVA_HOME="{jdk}"')
    if is_win() and not check_only:
        print("note: a new terminal is needed before freshly installed tools are on PATH.")
    print("\nNext: ./gradlew test    (or ./gradlew executableJar)")
    sys.exit(missing)


if __name__ == "__main__":
    main()
