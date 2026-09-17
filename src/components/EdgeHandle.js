import React, { useState } from 'react';
import { StyleSheet, useWindowDimensions, Text } from 'react-native';
import { GestureDetector, Gesture } from 'react-native-gesture-handler';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  withDelay,
  withRepeat,
  runOnJS,
  Easing,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../theme/colors';

// --- Tunable geometry -------------------------------------------------
const BAR_LEN = 64;    // resting handle length (px)
const BAR_THICK = 10;  // resting handle thickness (px)
const BALL_D = 46;     // sphere diameter while dragging (px)
const PIECE_H = 54;    // active piece height, including the upper bump
const MARGIN = 18;     // keep-out margin from top/bottom safe areas
const RING_D = 40;     // ripple ring base size
const LONG_PRESS_MS = 300;

function clamp(v, min, max) {
  'worklet';
  return Math.max(min, Math.min(max, v));
}

export default function EdgeHandle() {
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();

  const topBound = insets.top + MARGIN;
  const bottomBound = height - insets.bottom - MARGIN;

  const [statusText, setStatusText] = useState('');

  // Position of the handle/sphere's CENTER.
  const posX = useSharedValue(width - BAR_THICK / 2);
  const posY = useSharedValue(height / 2);
  const startPos = useSharedValue({ x: 0, y: 0 });

  // 0 = resting bar, 1 = fully-formed sphere.
  const ballProgress = useSharedValue(0);
  // 1 while the user is actively allowed to drag (after long-press fired).
  const dragActive = useSharedValue(0);

  // Ripple ring animation drivers (0 -> 1 repeating).
  const ring1 = useSharedValue(0);
  const ring2 = useSharedValue(0);
  const wavesOn = useSharedValue(0);

  // Directional "trail" blob shown while actively moving fast.
  const trailOpacity = useSharedValue(0);
  const trailScaleX = useSharedValue(1);
  const trailAngle = useSharedValue(0);
  const gazeX = useSharedValue(0);

  const setStatusJS = (t) => setStatusText(t);

  // --- Worklet helpers --------------------------------------------------
  const startWaves = () => {
    'worklet';
    wavesOn.value = 1;
    ring1.value = 0;
    ring2.value = 0;
    ring1.value = withRepeat(
      withTiming(1, { duration: 1300, easing: Easing.out(Easing.quad) }),
      -1,
      false
    );
    ring2.value = withDelay(
      650,
      withRepeat(
        withTiming(1, { duration: 1300, easing: Easing.out(Easing.quad) }),
        -1,
        false
      )
    );
  };

  const stopWaves = () => {
    'worklet';
    wavesOn.value = withTiming(0, { duration: 300 });
  };

  // --- Gestures -----------------------------------------------------------
  // LongPress "arms" dragging and morphs the bar into a sphere.
  const longPress = Gesture.LongPress()
    .minDuration(LONG_PRESS_MS)
    .onStart(() => {
      ballProgress.value = withTiming(1, {
        duration: 220,
        easing: Easing.out(Easing.back(1.4)),
      });
      dragActive.value = 1;
      startWaves();
      runOnJS(setStatusJS)('drag up, down, or to the other side');
    });

  // Pan tracks the touch continuously; it only *moves* the handle once
  // dragActive is 1 (i.e. after the long press has fired).
  const pan = Gesture.Pan()
    .onStart(() => {
      startPos.value = { x: posX.value, y: posY.value };
    })
    .onUpdate((e) => {
      if (dragActive.value !== 1) return;

      posX.value = clamp(startPos.value.x + e.translationX, -BALL_D, width + BALL_D);
      posY.value = clamp(startPos.value.y + e.translationY, topBound, bottomBound);

      const speed = Math.min(1, Math.hypot(e.velocityX, e.velocityY) / 1200);
      gazeX.value = withTiming(clamp(e.velocityX / 500, -1, 1), { duration: 90 });
      if (speed > 0.04) {
        wavesOn.value = 0;
        trailAngle.value = (Math.atan2(e.velocityY, e.velocityX) * 180) / Math.PI;
        trailScaleX.value = 1 + speed * 1.6;
        trailOpacity.value = 0.22 + speed * 0.45;
      } else {
        wavesOn.value = 1;
        trailOpacity.value = withTiming(0, { duration: 150 });
      }
    })
    .onEnd(() => {
      if (dragActive.value !== 1) return;
      dragActive.value = 0;
      trailOpacity.value = withTiming(0, { duration: 150 });
      wavesOn.value = 0;
      gazeX.value = withTiming(0, { duration: 180 });

      const targetSide = posX.value < width / 2 ? 'left' : 'right';
      const targetX = targetSide === 'left' ? -BAR_THICK / 2 : width - BAR_THICK / 2;
      const clampedY = clamp(
        posY.value,
        topBound + BAR_LEN / 2,
        bottomBound - BAR_LEN / 2
      );

      runOnJS(setStatusJS)('settling into place...');

      posX.value = withTiming(targetX, { duration: 360, easing: Easing.out(Easing.cubic) });
      posY.value = withTiming(
        clampedY,
        { duration: 360, easing: Easing.out(Easing.cubic) },
        (finished) => {
          if (finished) {
            ballProgress.value = withTiming(0, { duration: 240 });
            stopWaves();
            runOnJS(setStatusJS)('');
          }
        }
      );
    });

  const composed = Gesture.Simultaneous(longPress, pan);

  // --- Animated styles ------------------------------------------------
  const shapeStyle = useAnimatedStyle(() => {
    const w = BAR_THICK + (BALL_D - BAR_THICK) * ballProgress.value;
    const h = BAR_LEN + (PIECE_H - BAR_LEN) * ballProgress.value;
    const r = BAR_THICK / 2 + (BALL_D / 2 - BAR_THICK / 2) * ballProgress.value;

    return {
      position: 'absolute',
      left: posX.value - w / 2,
      top: posY.value - h / 2,
      width: w,
      height: h,
      borderRadius: r,
      opacity: 0.58,
      shadowColor: '#3B3F8F',
      shadowOpacity: 0.18 + ballProgress.value * 0.15,
      shadowRadius: 4 + ballProgress.value * 4,
      shadowOffset: { width: 0, height: 1 },
      elevation: 2 + ballProgress.value * 3,
    };
  });

  const pieceBodyStyle = useAnimatedStyle(() => ({
    opacity: ballProgress.value,
    backgroundColor: colors.handleFillActive,
    transform: [{ scale: 0.9 + ballProgress.value * 0.1 }],
  }));

  const barStyle = useAnimatedStyle(() => ({
    opacity: 1 - ballProgress.value,
    backgroundColor: colors.handleFill,
  }));

  const pieceBumpStyle = useAnimatedStyle(() => ({
    opacity: ballProgress.value,
    backgroundColor: colors.handleFillActive,
    transform: [{ scale: 0.8 + ballProgress.value * 0.2 }],
  }));

  const eyeStyle = useAnimatedStyle(() => ({
    opacity: ballProgress.value,
  }));

  const leftPupilStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: gazeX.value * 2.2 }],
  }));

  const rightPupilStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: gazeX.value * 2.2 }],
  }));

  const ring1Style = useAnimatedStyle(() => ({
    position: 'absolute',
    left: posX.value - RING_D / 2,
    top: posY.value - RING_D / 2,
    width: RING_D,
    height: RING_D,
    borderRadius: RING_D / 2,
    borderWidth: 1.5,
    borderColor: colors.wave,
    opacity: wavesOn.value * 0.42 * (1 - ring1.value),
    transform: [{ scale: 0.9 + ring1.value * 1.3 }],
  }));

  const ring2Style = useAnimatedStyle(() => ({
    position: 'absolute',
    left: posX.value - RING_D / 2,
    top: posY.value - RING_D / 2,
    width: RING_D,
    height: RING_D,
    borderRadius: RING_D / 2,
    borderWidth: 1.5,
    borderColor: colors.wave,
    opacity: wavesOn.value * 0.42 * (1 - ring2.value),
    transform: [{ scale: 0.9 + ring2.value * 1.3 }],
  }));

  const trailStyle = useAnimatedStyle(() => ({
    position: 'absolute',
    left: posX.value - RING_D / 2,
    top: posY.value - RING_D / 2,
    width: RING_D,
    height: RING_D,
    borderRadius: RING_D / 2,
    backgroundColor: colors.waveTrail,
    opacity: trailOpacity.value,
    transform: [
      { rotate: `${trailAngle.value}deg` },
      { scaleX: trailScaleX.value },
      { scaleY: 0.7 },
    ],
  }));

  return (
    <>
      <Animated.View pointerEvents="none" style={ring1Style} />
      <Animated.View pointerEvents="none" style={ring2Style} />
      <Animated.View pointerEvents="none" style={trailStyle} />
      <GestureDetector gesture={composed}>
        <Animated.View style={shapeStyle}>
          <Animated.View style={[styles.bar, barStyle]} />
          <Animated.View style={[styles.pieceBody, pieceBodyStyle]} />
          <Animated.View style={[styles.pieceBump, pieceBumpStyle]} />
          <Animated.View pointerEvents="none" style={[styles.eye, styles.leftEye, eyeStyle]}>
            <Animated.View style={[styles.pupil, leftPupilStyle]} />
          </Animated.View>
          <Animated.View pointerEvents="none" style={[styles.eye, styles.rightEye, eyeStyle]}>
            <Animated.View style={[styles.pupil, rightPupilStyle]} />
          </Animated.View>
        </Animated.View>
      </GestureDetector>
      {statusText ? (
        <Animated.View pointerEvents="none" style={styles.statusPill}>
          <Text style={styles.statusText}>{statusText}</Text>
        </Animated.View>
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  bar: {
    position: 'absolute',
    left: (BALL_D - BAR_THICK) / 2,
    top: 0,
    width: BAR_THICK,
    height: BAR_LEN,
    borderRadius: BAR_THICK / 2,
  },
  pieceBody: {
    position: 'absolute',
    left: 0,
    bottom: 0,
    width: BALL_D,
    height: 38,
    borderRadius: 19,
  },
  pieceBump: {
    position: 'absolute',
    left: 11,
    top: 0,
    width: 24,
    height: 28,
    borderRadius: 14,
  },
  eye: {
    position: 'absolute',
    top: 27,
    width: 6,
    height: 9,
    borderRadius: 4,
    backgroundColor: '#30355F',
    alignItems: 'center',
    justifyContent: 'center',
  },
  leftEye: {
    left: 14,
  },
  rightEye: {
    right: 14,
  },
  pupil: {
    width: 2,
    height: 4,
    borderRadius: 2,
    backgroundColor: '#161A3A',
  },
  statusPill: {
    position: 'absolute',
    bottom: 40,
    alignSelf: 'center',
    backgroundColor: 'rgba(43,47,58,0.85)',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 16,
  },
  statusText: {
    color: '#FFFFFF',
    fontSize: 13,
  },
});
