import { completeFactors } from './mfa';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { backend as apiUrl, issuer, mobile, setup, variants, web } from './environment';

const technician = '00000000-0000-4000-8000-000000000002';
const password = process.env['SERVICEOPS_DEMO_PASSWORD'] ?? 'serviceops-demo';
async function login(page: Page, origin: string, user: string, variant: typeof variants[number]) {
  await setup(page, variant);
  await page.goto(origin);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await completeFactors(page, user, origin);
  await expect(page).toHaveURL(origin + '/');
}

async function auth(api: APIRequestContext, username: string) {
  const response = await api.post(issuer + '/protocol/openid-connect/token', { form: {
    grant_type: 'password', client_id: 'serviceops-test', username, password,
  } });
  expect(response.ok()).toBeTruthy();
  return { Authorization: 'Bearer ' + (await response.json()).access_token };
}

async function seed(api: APIRequestContext, backend: string, title: string) {
  const creator = await auth(api, 'requester');
  const manager = await auth(api, 'manager');
  const tech = await auth(api, 'technician');
  const created = await api.post(backend + '/requests', {
    headers: { ...creator, 'Idempotency-Key': crypto.randomUUID() }, data: { title },
  });
  expect(created.status()).toBe(201);
  const request = await created.json();
  const url = backend + '/requests/' + request.id;
  const assigned = await api.post(url + '/assign', {
    headers: manager, data: { technicianId: technician, expectedVersion: request.version },
  });
  expect(assigned.status()).toBe(204);
  const assignedState = await (await api.get(url, { headers: tech })).json();
  const started = await api.post(url + '/start', {
    headers: tech, data: { expectedVersion: assignedState.version },
  });
  expect(started.status()).toBe(200);
  return { url, tech, version: (await started.json()).version };
}

for (const variant of variants) {
  const backend = apiUrl(variant);

  test(`Angular et Kanban · ${variant}`, async ({ page }) => {
    await login(page, web, 'manager', variant);
    await expect(page.getByRole('heading', { name: 'Demandes d’intervention' })).toBeVisible();
    const home = page.getByRole('link', { name: 'ServiceOps Lab' });
    await home.hover();
    await expect(home).toHaveCSS('text-decoration-line', 'underline');
    const title = 'Porte Kanban ' + crypto.randomUUID().slice(0, 10);
    await page.getByLabel('Titre de la demande').fill(title);
    await page.getByRole('button', { name: 'Créer', exact: true }).click();
    await page.getByRole('button', { name: 'Kanban', exact: true }).click();
    await page.getByLabel('Recherche').fill(title);
    const open = page.locator('[data-status="OPEN"]');
    const card = open.getByRole('button', { name: title });
    await expect(card).toBeVisible();
    await card.hover();
    await expect(card).toHaveCSS('text-decoration-line', 'underline');
    const assign = open.locator('article').filter({
      has: page.getByRole('button', { name: title, exact: true }),
    }).getByRole('button', { name: 'Affecter', exact: true });
    const dialog = page.getByRole('dialog', { name: 'Affecter la demande' });
    await assign.click();
    await expect(dialog.getByRole('combobox', { name: 'Technicien', exact: true })).toBeFocused();
    await expect(dialog.getByRole('button', { name: 'Fermer l’affectation' })).toBeEnabled();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(assign).toBeFocused();
    const assigned = page.locator('[data-status="ASSIGNED"]');
    const source = await card.boundingBox();
    const target = await assigned.boundingBox();
    if (!source || !target) throw new Error('Carte ou colonne inaccessible');
    await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2);
    await page.mouse.down();
    await page.mouse.move(target.x + target.width / 2, target.y + 100, { steps: 16 });
    await page.mouse.up();
    await page.getByRole('combobox', { name: 'Technicien', exact: true }).selectOption(technician);
    const assignmentPath = '**/api/v1/requests/*/assign';
    await page.route(assignmentPath, route => route.fulfill({
      status: 409,
      contentType: 'application/problem+json',
      body: JSON.stringify({ status: 409, code: 'VERSION_CONFLICT', detail: 'La demande a changé.' }),
    }), { times: 1 });
    await page.getByRole('button', { name: 'Confirmer', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('La demande a changé.');
    await expect(card).toBeVisible();
    await page.getByRole('button', { name: 'Confirmer', exact: true }).click();
    await expect(assigned.getByRole('button', { name: title })).toBeVisible();
    await expect(open.getByRole('button', { name: title })).toHaveCount(0);
    await assigned.getByRole('button', { name: title }).click();
    await expect(page.getByRole('heading', { name: title })).toBeVisible();
    await expect(page.getByText('Affectée · Version 1')).toBeVisible();
  });

  test(`Ionic hors ligne et réponse perdue · ${variant}`, async ({ page, context, request }) => {
    const title = 'Intervention mobile ' + crypto.randomUUID().slice(0, 10);
    const { url, tech, version } = await seed(request, backend, title);
    await login(page, mobile, 'technician', variant);
    await page.evaluate(async () => { await navigator.serviceWorker.ready; });
    await expect(page.getByRole('button', { name: new RegExp(title) })).toBeVisible();
    await page.getByRole('button', { name: new RegExp(title) }).click();
    await page.getByText('Rédiger un compte rendu', { exact: true }).click();
    await context.setOffline(true);
    await expect(page.getByText('Hors ligne', { exact: true })).toBeVisible();
    const report = 'Intervention clôturée côté technicien en mode hors ligne.';
    await page.getByLabel('Compte rendu', { exact: true }).fill(report);
    await expect.poll(() => page.evaluate(() =>
      Object.values(localStorage).some(value => value.includes('mode hors ligne'))
    )).toBeTruthy();
    await page.reload();
    await page.getByRole('button', { name: new RegExp(title) }).click();
    await expect(page.getByLabel('Compte rendu', { exact: true })).toHaveValue(report);
    await context.setOffline(false);
    await expect(page.getByText('En ligne', { exact: true })).toBeVisible();
    const concurrentEdit = await request.post(url + '/comments', {
      headers: tech, data: { expectedVersion: version, body: 'Autre modification pendant la coupure.' },
    });
    expect(concurrentEdit.status()).toBe(201);
    await page.getByText('Confirmer l’envoi', { exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Intervention modifiée' })).toBeVisible();
    await expect(page.getByLabel('Compte rendu', { exact: true })).toHaveValue(report);
    await page.getByText('Utiliser la version actuelle', { exact: true }).click();
    const resolvePath = url + '/resolve';
    await page.route(resolvePath, async route => {
      const result = await route.fetch();
      expect(result.status()).toBe(200);
      await route.abort('connectionreset');
    });
    await page.getByText('Confirmer l’envoi', { exact: true }).click();
    await expect(page.getByText('Relancer la même commande', { exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('Serveur injoignable');
    await page.unroute(resolvePath);
    await page.getByText('Relancer la même commande', { exact: true }).click();
    await expect(page.getByText('Compte rendu enregistré.', { exact: true })).toBeVisible();
    const resolved = await (await request.get(url, { headers: tech })).json();
    expect(resolved.status).toBe('RESOLVED');
    expect(resolved.report).toBe(report);
    expect(resolved.version).toBe(version + 2);
  });
}
