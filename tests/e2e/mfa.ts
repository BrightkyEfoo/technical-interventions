import { createHmac } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { setTimeout } from 'node:timers/promises';
import { expect, type Page } from '@playwright/test';

const stateFile = process.env['SERVICEOPS_TEST_MFA_STATE']!;

function otp(secret: string, step: number) {
  const counter = Buffer.alloc(8);
  counter.writeBigInt64BE(BigInt(step));
  // Keycloak posts the raw secret in this field and Base32-encodes it for authenticator apps.
  const hash = createHmac('sha1', Buffer.from(secret, 'utf8')).update(counter).digest();
  return String((hash.readUInt32BE(hash.at(-1)! & 15) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

export async function completeFactors(page: Page, username: string, origin: string, newPassword?: string) {
  await page.waitForFunction(
    (target: string) => location.origin === target || !!document.querySelector('#password-new,input[name="otp"],input[name="totp"]'),
    origin,
  );
  if (new URL(page.url()).origin === origin) return;
  if (await page.locator('#password-new').count()) {
    if (!newPassword) throw new Error('Changement de mot de passe inattendu.');
    await page.locator('#password-new').fill(newPassword);
    await page.locator('#password-confirm').fill(newPassword);
    await page.locator('#kc-passwd-update-form [type="submit"]').click();
    return completeFactors(page, username, origin);
  }
  const field = page.locator('input[name="totp"], input[name="otp"]').first();
  const secrets: Record<string, { secret: string; step: number }> = existsSync(stateFile)
    ? JSON.parse(readFileSync(stateFile, 'utf8')) : {};
  const setup = page.locator('input[name="totpSecret"]');
  const enrolling = await setup.count() > 0;
  if (enrolling) {
    const qr = page.locator('#kc-totp-secret-qr-code');
    await expect(qr).toBeVisible();
    await expect.poll(() => qr.evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
    await expect(page.locator('#kc-totp-secret-key')).toBeVisible();
  }
  const secret = enrolling ? await setup.inputValue() : secrets[username]?.secret;
  if (!secret) throw new Error('Clé MFA de test indisponible.');
  let step = Math.floor(Date.now() / 30_000);
  if (secrets[username]?.step === step) {
    await setTimeout((step + 1) * 30_000 - Date.now() + 200);
    step = Math.floor(Date.now() / 30_000);
  }
  await field.fill(otp(secret, step));
  if (await page.locator('input[name="userLabel"]').count()) {
    await page.locator('input[name="userLabel"]').fill('Test local');
  }
  await field.locator('xpath=ancestor::form').locator('[type="submit"]').first().click();
  if (new URL(page.url()).origin !== origin) {
    throw new Error('Keycloak n’a pas validé le code de test.');
  }
  secrets[username] = { secret, step };
  writeFileSync(stateFile, JSON.stringify(secrets), { mode: 0o600 });
}
