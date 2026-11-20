import type { Page } from '@playwright/test';

export const issuer = process.env['SERVICEOPS_ISSUER']!;
export const web = `http://localhost:${process.env['SERVICEOPS_WEB_PORT']}`;
export const mobile = `http://localhost:${process.env['SERVICEOPS_MOBILE_WEB_PORT']}`;
export const variants = ['java', 'kotlin'] as const;
const ports = { java: process.env['SERVICEOPS_JAVA_PORT']!, kotlin: process.env['SERVICEOPS_KOTLIN_PORT']! };

export const backend = (variant: typeof variants[number]) => `http://127.0.0.1:${ports[variant]}/api/v1`;

export function setup(page: Page, variant: typeof variants[number]) {
  return page.addInitScript(({ variant, issuer, ports }) => {
    localStorage.setItem('serviceops.variant', variant);
    localStorage.setItem('serviceops.authority', issuer);
    localStorage.setItem('serviceops.javaPort', ports.java);
    localStorage.setItem('serviceops.kotlinPort', ports.kotlin);
  }, { variant, issuer, ports });
}
