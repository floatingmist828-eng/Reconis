import { createHash } from 'node:crypto';
import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';

const readJson = async filename => JSON.parse((await readFile(filename, 'utf8')).replace(/^\uFEFF/, ''));

// Read once before business verification and keep those bytes for PDF checks. Never fetch an archive.
export async function loadVerificationJar({ root, jarFile, expectedSha256 } = {}) {
  let filename = jarFile;
  let releaseId = null;
  if (!filename) {
    const pointerPath = path.join(root, 'artifacts', 'releases', 'current.json');
    let pointer;
    try { pointer = await readJson(pointerPath); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (pointer) {
      if (pointer.schemaVersion !== 1 || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(pointer.releaseId)
        || !/^[A-Fa-f0-9]{64}$/.test(pointer.jarSha256)) throw new Error('Invalid current release pointer.');
      releaseId = pointer.releaseId;
      const directory = path.join(root, 'artifacts', 'releases', releaseId);
      const manifest = await readJson(path.join(directory, 'manifest.json'));
      if (manifest.schemaVersion !== 1 || manifest.releaseId !== releaseId || manifest.jarFile !== 'reconis-server.jar'
        || String(manifest.jarSha256).toLowerCase() !== pointer.jarSha256.toLowerCase()) {
        throw new Error('Current release manifest does not match the selected release.');
      }
      filename = path.join(directory, manifest.jarFile);
      expectedSha256 = pointer.jarSha256;
    } else {
      filename = path.join(root, 'backend', 'target', 'reconis-server-1.0.0.jar');
    }
  }
  if (typeof filename !== 'string' || /^[a-z][a-z0-9+.-]*:\/\//i.test(filename) || /^[/\\]{2}/.test(filename)) {
    throw new Error('Verification JAR must be a local file, not a URL or network share.');
  }
  const resolved = await realpath(path.resolve(root, filename));
  if (/^[/\\]{2}/.test(resolved) || path.extname(resolved).toLowerCase() !== '.jar') {
    throw new Error('Verification JAR must resolve to a local .jar file.');
  }
  const info = await stat(resolved);
  if (!info.isFile() || info.size < 4 || info.size > 512 * 1024 * 1024) throw new Error('Invalid verification JAR file.');
  const bytes = await readFile(resolved);
  if (bytes.readUInt32LE(0) !== 0x04034b50) throw new Error('Verification JAR is not a ZIP archive.');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  if (expectedSha256 && (!/^[A-Fa-f0-9]{64}$/.test(expectedSha256) || sha256 !== expectedSha256.toLowerCase())) {
    throw new Error('Verification JAR SHA256 does not match the selected archive.');
  }
  return { path: resolved, sha256, releaseId, bytes };
}
