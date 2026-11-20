import { completeFactors } from './mfa';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { backend, issuer, mobile, setup, variants, web } from './environment';

const superPassword = process.env['KEYCLOAK_SUPER_ADMIN_PASSWORD']!;
test.use({ trace: 'off' });

async function signIn(page: Page, username: string, password: string) {
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
}

async function getToken(request: APIRequestContext, username: string, password: string) {
  const response = await request.post(issuer + '/protocol/openid-connect/token', {
    form: { grant_type: 'password', client_id: 'serviceops-test', username, password },
  });
  expect(response.ok()).toBeTruthy();
  return (await response.json()).access_token as string;
}

for (const variant of variants) {
  const origin = variant === 'java' ? web : mobile;
  const url = backend(variant);
  test(`Inscription demandeur réelle · ${variant}`, async ({ page, request }) => {
    await setup(page, variant);
    await page.goto(origin);
    await page.getByRole('button', { name: 'Créer un compte demandeur' }).click();

    const username = 'demandeur-' + crypto.randomUUID().slice(0, 10);
    const password = crypto.randomUUID();
    await page.locator('#firstName').fill('Camille');
    await page.locator('#lastName').fill('Atelier');
    await page.locator('#email').fill(username + '@serviceops.com');
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await page.locator('#password-confirm').fill(password);
    await expect(page.locator('input[name="role"],select[name="role"]')).toHaveCount(0);
    await page.locator('#kc-register-form').evaluate(form => {
      const claimedRole = document.createElement('input');
      claimedRole.type = 'hidden';
      claimedRole.name = 'realmRoles';
      claimedRole.value = 'SUPER_ADMIN';
      form.append(claimedRole);
    });
    await page.locator('#kc-register-form [type="submit"]').click();
    await completeFactors(page, username, origin);
    await expect(page).toHaveURL(origin + '/');

    const token = await getToken(request, username, password);
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
    expect(payload.realm_access.roles.filter((role: string) =>
      ['REQUESTER', 'TECHNICIAN', 'MANAGER', 'SUPER_ADMIN'].includes(role))).toEqual(['REQUESTER']);

    const headers = { Authorization: 'Bearer ' + token };
    expect((await request.get(url + '/technicians', { headers })).status()).toBe(403);
    const created = await request.post(url + '/requests', {
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      data: { title: 'Demande après inscription' },
    });
    expect(created.status()).toBe(201);
    expect((await created.json()).ownerId).toBe(payload.sub);
  });

  test(`Comptes et interventions du super admin · ${variant}`, async ({ page, browser, request }) => {
    if (variant === 'kotlin') await page.setViewportSize({ width: 390, height: 844 });
    await setup(page, variant);
    await page.goto(web);
    await signIn(page, 'superadmin', superPassword);
    await completeFactors(page, 'superadmin', web);
    await expect(page.getByRole('heading', { name: 'Gestion des comptes' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Demandes d’intervention' })).toHaveCount(0);

    const requester = await getToken(request, 'requester', 'serviceops-demo');
    const title = 'Intervention visible du super admin ' + crypto.randomUUID().slice(0, 8);
    const createdRequest = await request.post(url + '/requests', {
      headers: { Authorization: 'Bearer ' + requester, 'Idempotency-Key': crypto.randomUUID() },
      data: { title },
    });
    expect(createdRequest.status()).toBe(201);
    await page.getByRole('button', { name: 'Interventions', exact: true }).click();
    await page.getByLabel('Recherche').fill(title);
    await page.getByRole('button', { name: title, exact: true }).click();
    const detail = page.getByRole('complementary', { name: 'Détail de la demande' });
    await expect(detail.getByRole('heading', { name: title })).toBeVisible();
    await expect(detail.getByText('Création · À affecter · v0')).toBeVisible();
    await expect(detail.getByLabel('Message')).toHaveCount(0);
    await expect(page.getByLabel('Titre de la demande')).toHaveCount(0);
    await detail.getByRole('button', { name: 'Fermer le détail' }).click();
    await page.getByRole('button', { name: 'Comptes', exact: true }).click();
    const search = page.getByRole('searchbox', { name: 'Rechercher un utilisateur' });
    await search.fill('superadmin');
    const table = page.getByRole('table', { name: 'Utilisateurs' });
    await expect(table.getByRole('row').filter({ hasText: 'superadmin' })).toContainText('Super admin');

    const username = 'staff-' + crypto.randomUUID().slice(0, 10);
    const temporaryPassword = crypto.randomUUID();
    const role = variant === 'java' ? 'MANAGER' : 'TECHNICIAN';
    await page.getByRole('button', { name: 'Créer un compte', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Nouveau compte' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('Prénom', { exact: true })).toBeFocused();
    await page.getByLabel('Rôle', { exact: true }).selectOption(role);
    await page.getByLabel('Identifiant', { exact: true }).fill(username);
    await page.getByLabel('Courriel', { exact: true }).fill(username + '@serviceops.com');
    await page.getByLabel('Prénom', { exact: true }).fill('Camille');
    await page.getByLabel('Nom', { exact: true }).fill('Atelier');
    await page.getByLabel('Mot de passe temporaire').fill(temporaryPassword);
    await page.getByRole('button', { name: 'Créer le compte' }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole('status').filter({ hasText: username })).toBeVisible();
    await search.fill(username);
    const row = table.getByRole('row').filter({ hasText: username });
    await expect(row).toContainText(role === 'MANAGER' ? 'Responsable' : 'Technicien');
    await expect(row).toContainText('Actif');
    await page.getByRole('button', { name: 'Créer un compte', exact: true }).click();
    await expect(dialog.getByLabel('Mot de passe temporaire')).toHaveValue('');
    await dialog.getByRole('button', { name: 'Fermer le formulaire' }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole('button', { name: 'Créer un compte', exact: true })).toBeFocused();

    const fresh = await browser.newPage();
    try {
      await setup(fresh, variant);
      await fresh.goto(web);
      await signIn(fresh, username, temporaryPassword);
      await completeFactors(fresh, username, web, crypto.randomUUID());
      await expect(fresh.getByRole('heading', { name: 'Demandes d’intervention' })).toBeVisible();
      await expect(fresh.getByText(role, { exact: true })).toBeVisible();
      await expect(fresh.getByRole('heading', { name: 'Gestion des comptes' })).toHaveCount(0);
    } finally {
      await fresh.close();
    }
  });
}
