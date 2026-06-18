import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.example.panels',
  appName: 'Panels Capacitor',
  webDir: 'dist',
  server: {
    url: 'https://app.example.test'
  },
  plugins: {
    SplashScreen: {
      launchAutoHide: false
    },
    PushNotifications: {}
  }
};

export default config;
