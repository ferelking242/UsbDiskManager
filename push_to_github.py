#!/usr/bin/env python3
"""
Push all local changes to GitHub using a Personal Access Token.

Usage:
    python push_to_github.py

Environment variables required:
    GITHUB_TOKEN  — GitHub Personal Access Token (classic or fine-grained)
                    Set this as a Replit secret named GITHUB_TOKEN

The script reads the remote URL from git config, injects the token,
commits any uncommitted changes, and pushes to the main/master branch.
"""

import os
import subprocess
import sys


def run(cmd, check=True, capture=False):
    """Run a shell command and return output."""
    kwargs = {"shell": True, "text": True}
    if capture:
        kwargs["capture_output"] = True
    result = subprocess.run(cmd, **kwargs)
    if check and result.returncode != 0:
        print(f"[ERROR] Command failed: {cmd}")
        if capture:
            print(result.stderr)
        sys.exit(1)
    return result


def get_remote_url():
    """Get the current git remote URL."""
    result = run("git remote get-url origin", capture=True)
    return result.stdout.strip()


def inject_token(url, token):
    """Inject PAT into the remote URL for authentication."""
    if url.startswith("https://"):
        # https://github.com/user/repo.git -> https://TOKEN@github.com/user/repo.git
        url = url.replace("https://", f"https://{token}@")
    return url


def get_current_branch():
    """Get the current git branch name."""
    result = run("git branch --show-current", capture=True)
    return result.stdout.strip() or "main"


def main():
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        print("[ERROR] GITHUB_TOKEN environment variable is not set.")
        print("        Add it as a Replit Secret named GITHUB_TOKEN.")
        sys.exit(1)

    # Configure git identity if not set
    run("git config user.email 2>/dev/null || git config user.email 'bot@usbdiskmanager.app'", check=False)
    run("git config user.name 2>/dev/null || git config user.name 'USB Disk Manager Bot'", check=False)

    # Stage all changes
    print("[1/5] Staging all changes...")
    run("git add -A")

    # Check if there is anything to commit
    status = run("git status --porcelain", capture=True)
    if status.stdout.strip():
        print("[2/5] Committing changes...")
        # Get a short summary of changes for the commit message
        diff_stat = run("git diff --cached --stat --no-color", capture=True, check=False)
        summary = diff_stat.stdout.strip().split("\n")[-1] if diff_stat.stdout.strip() else "update"
        message = f"feat: {summary}"
        run(f'git commit -m "{message}"')
    else:
        print("[2/5] Nothing to commit — working tree clean.")

    # Get remote URL and branch
    print("[3/5] Configuring remote with token...")
    remote_url = get_remote_url()
    auth_url = inject_token(remote_url, token)
    branch = get_current_branch()
    print(f"      Branch: {branch}")
    print(f"      Remote: {remote_url.split('@')[-1]}")  # Hide token in output

    # Set the authenticated remote temporarily
    run(f"git remote set-url origin '{auth_url}'")

    # Push
    print(f"[4/5] Pushing to origin/{branch}...")
    result = run(f"git push origin {branch}", capture=True, check=False)
    if result.returncode != 0:
        print(f"[ERROR] Push failed:\n{result.stderr}")
        # Restore original URL (without token)
        run(f"git remote set-url origin '{remote_url}'")
        sys.exit(1)

    # Restore original URL (without token)
    run(f"git remote set-url origin '{remote_url}'")

    print("[5/5] Done! Changes pushed successfully.")
    print(f"      GitHub Actions CI should trigger automatically.")
    print(f"      Check: https://github.com/{get_repo_slug(remote_url)}/actions")


def get_repo_slug(url):
    """Extract user/repo from remote URL."""
    url = url.rstrip("/").replace(".git", "")
    parts = url.replace("https://github.com/", "").replace("git@github.com:", "")
    return parts


if __name__ == "__main__":
    main()
