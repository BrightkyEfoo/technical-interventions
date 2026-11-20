import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'cm.brightky.serviceops',
  appName: 'ServiceOps Lab',
  webDir: 'dist/mobile/browser',
  ios: { path: 'apps/mobile-ionic/ios' },
  android: { path: 'apps/mobile-ionic/android' },
};

export default config;
