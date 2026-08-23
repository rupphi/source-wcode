import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { resolveReleaseVersion } from "./release-version.mjs";

const LEGACY_WINDOWS_UPGRADE_UUID = "D0FC7057-DA6C-3181-ADF9-C21DB2C9152A";

async function createProject(overrides = {}) {
  const root = await mkdtemp(path.join(tmpdir(), "wcode-release-version-"));
  const files = {
    "pom.xml": `<?xml version="1.0"?>
<project>
  <version>1.1.10</version>
  <properties><app.version>1.1.10</app.version></properties>
</project>
`,
    ...overrides,
  };
  for (const [relativePath, content] of Object.entries(files)) {
    const target = path.join(root, relativePath);
    await writeFile(target, content, "utf8");
  }
  return root;
}

test("returns the single version declared consistently by the source tree", async () => {
  const root = await createProject();
  try {
    const release = await resolveReleaseVersion({ root });

    assert.equal(release.version, "1.1.10");
    assert.equal(release.tag, "v1.1.10");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("accepts a release tag only when it exactly matches the source version", async () => {
  const root = await createProject();
  try {
    const release = await resolveReleaseVersion({ root, refType: "tag", refName: "v1.1.10" });

    assert.equal(release.version, "1.1.10");
    assert.equal(release.shouldPublish, true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("manual and branch runs resolve the source version but cannot publish", async () => {
  const root = await createProject();
  try {
    const release = await resolveReleaseVersion({ root, refType: "branch", refName: "main" });

    assert.equal(release.version, "1.1.10");
    assert.equal(release.shouldPublish, false);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects inconsistent source manifests", async () => {
  const root = await createProject({
    "pom.xml": `<?xml version="1.0"?>
<project>
  <version>1.1.10</version>
  <properties><app.version>1.1.11</app.version></properties>
</project>
`,
  });
  try {
    await assert.rejects(
      () => resolveReleaseVersion({ root }),
      /Version declarations do not match/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects malformed or mismatched release tags", async () => {
  const root = await createProject();
  try {
    await assert.rejects(
      () => resolveReleaseVersion({ root, refType: "tag", refName: "release-1.1.10" }),
      /Release tags must use vMAJOR\.MINOR\.PATCH/,
    );
    await assert.rejects(
      () => resolveReleaseVersion({ root, refType: "tag", refName: "v1.1.11" }),
      /does not match source version 1\.1\.10/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("keeps the Windows installer identity compatible with released 1.1.8 and 1.1.9", async () => {
  const workflow = await readFile(
    new URL("../.github/workflows/release.yml", import.meta.url),
    "utf8",
  );
  const declaration = workflow.match(/^\s*WINDOWS_UPGRADE_UUID:\s*([0-9A-F-]+)\s*$/m);
  const installerUses = workflow.match(/--win-upgrade-uuid \$env:WINDOWS_UPGRADE_UUID/g) ?? [];

  assert.equal(declaration?.[1], LEGACY_WINDOWS_UPGRADE_UUID);
  assert.equal(installerUses.length, 2, "both MSI and EXE must reuse the upgrade UUID");
});
