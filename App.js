import React, { useEffect } from 'react';
import { StyleSheet, View, Text } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { Alert, AppState, NativeModules, Platform } from 'react-native';

import { colors } from './src/theme/colors';

export default function App() {
  useEffect(() => {
    if (Platform.OS !== 'android' || !NativeModules.FloatingOverlay) return undefined;

    let permissionPromptShown = false;
    const startOverlay = async () => {
      try {
        const allowed = await NativeModules.FloatingOverlay.hasOverlayPermission();
        if (allowed) {
          await NativeModules.FloatingOverlay.startOverlay();
        } else if (!permissionPromptShown) {
          permissionPromptShown = true;
          Alert.alert(
            'Allow Recall to display over other apps.',
            'This keeps the Recall handle visible on your Home Screen and above other apps.',
            [{ text: 'Not now', style: 'cancel' }, { text: 'Allow', onPress: () => NativeModules.FloatingOverlay.openOverlaySettings() }]
          );
        }
      } catch {}
    };

    startOverlay();
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') startOverlay();
    });
    return () => subscription.remove();
  }, []);

  return (
    <GestureHandlerRootView style={styles.flexFill}>
      <SafeAreaProvider>
        <StatusBar style="dark" />
        <View style={styles.root}>
          {/* Placeholder app content — kept intentionally minimal, exactly
              as in the original concept: this screen exists only to give
              the handle something to float over. */}
          <SafeAreaView style={styles.content}>
            <Text style={styles.title}>فكّرني</Text>
            <Text style={styles.subtitle}>Fekkerni</Text>
            <Text style={styles.hint}>
              Press and hold the small handle on the edge of the screen, then
              drag it up, down, or across to the other side.
            </Text>
          </SafeAreaView>
        </View>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  flexFill: { flex: 1 },
  root: { flex: 1, backgroundColor: colors.background },
  content: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 32,
  },
  title: {
    fontSize: 34,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 16,
    color: colors.textSecondary,
    marginBottom: 24,
  },
  hint: {
    fontSize: 14,
    color: colors.textSecondary,
    textAlign: 'center',
    lineHeight: 20,
  },
});
