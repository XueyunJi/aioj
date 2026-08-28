import fs from "node:fs";
import path from "node:path";

const [fragmentDirectory, releaseTag, commit, bundleSha, outputFile] = process.argv.slice(2);
if (!fragmentDirectory || !releaseTag || !commit || !bundleSha || !outputFile) {
  throw new Error("usage: assemble-manifest.mjs <fragments> <tag> <commit> <bundle-sha256> <output>");
}
if (!/^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(releaseTag)) throw new Error("invalid release tag");
if (!/^[0-9a-f]{40}$/.test(commit)) throw new Error("invalid Git commit");
if (!/^[0-9a-f]{64}$/.test(bundleSha)) throw new Error("invalid deployment bundle SHA-256");

const expected = ["gateway", "auth", "problem", "ai", "judge-worker", "sandbox", "web-user", "web-admin"];
const images = {};
for (const file of fs.readdirSync(fragmentDirectory).filter((name) => name.endsWith(".json"))) {
  const fragment = JSON.parse(fs.readFileSync(path.join(fragmentDirectory, file), "utf8"));
  if (!expected.includes(fragment.service)) throw new Error(`unexpected service ${fragment.service}`);
  if (images[fragment.service]) throw new Error(`duplicate service ${fragment.service}`);
  const expectedImage = `ghcr.io/mubai0628/aioj-${fragment.service}`;
  if (fragment.image !== expectedImage) throw new Error(`unexpected image namespace for ${fragment.service}`);
  if (!/^sha256:[0-9a-f]{64}$/.test(fragment.digest)) throw new Error(`invalid digest for ${fragment.service}`);
  images[fragment.service] = `${fragment.image}@${fragment.digest}`;
}
for (const service of expected) {
  if (!images[service]) throw new Error(`missing image fragment for ${service}`);
}

const orderedImages = Object.fromEntries(expected.map((service) => [service, images[service]]));
const manifest = {
  schemaVersion: 1,
  release: releaseTag,
  gitCommit: commit,
  platform: "linux/amd64",
  generatedAt: new Date().toISOString(),
  deploymentBundle: {
    name: "deployment-bundle.tar.gz",
    sha256: bundleSha,
  },
  images: orderedImages,
};

fs.writeFileSync(outputFile, JSON.stringify(manifest, null, 2) + "\n");
