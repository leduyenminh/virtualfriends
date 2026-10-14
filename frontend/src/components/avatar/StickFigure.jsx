import React, { useRef } from 'react';
import { useFrame } from '@react-three/fiber';
import { lipsync } from '../../tts/speech.js';

const PALETTE = { pink: '#ff6fae', blue: '#4c8dff', green: '#2fbf71' };

/** Brow tilt (rad), mouth curve, arm raise per emotion — enough to read the mood at a glance. */
const FACE = {
  neutral: { brow: 0, smile: 0, arms: 0.2, blush: 0 },
  happy: { brow: 0.15, smile: 1, arms: 1.1, blush: 0.4 },
  sad: { brow: -0.35, smile: -0.8, arms: 0.05, blush: 0 },
  angry: { brow: 0.5, smile: -0.5, arms: 0.6, blush: 0 },
  surprised: { brow: 0.35, smile: 0, arms: 1.6, blush: 0 },
  shy: { brow: -0.15, smile: 0.4, arms: 0.1, blush: 1 },
};

function Limb({ from = [0, 0, 0], length, angle = 0, color, thickness = 0.035 }) {
  // A capsule hanging from `from`, rotated around Z by `angle`.
  return (
    <group position={from} rotation={[0, 0, angle]}>
      <mesh position={[0, -length / 2, 0]}>
        <capsuleGeometry args={[thickness, length, 6, 12]} />
        <meshStandardMaterial color={color} />
      </mesh>
    </group>
  );
}

/** Procedural placeholder avatar: stick body, round head, animated brows/mouth/arms, idle bob and blink. */
export default function StickFigure({ color = 'pink', emotion = 'neutral' }) {
  const c = PALETTE[color] ?? color;
  const root = useRef();
  const head = useRef();
  const mouth = useRef();
  const leftArm = useRef();
  const rightArm = useRef();
  const eyes = useRef();
  const browL = useRef();
  const browR = useRef();
  const blush = useRef();
  const state = useRef({ ...FACE.neutral, blinkAt: 2 });

  useFrame(({ clock }, dt) => {
    const t = clock.elapsedTime;
    const goal = FACE[emotion] ?? FACE.neutral;
    const s = state.current;
    const k = 1 - Math.pow(0.001, dt); // frame-rate independent easing
    for (const key of ['brow', 'smile', 'arms', 'blush']) s[key] += (goal[key] - s[key]) * k;

    root.current.position.y = Math.sin(t * 2) * 0.02;
    head.current.rotation.z = Math.sin(t * 1.3) * 0.05 + (emotion === 'shy' ? 0.15 : 0);
    const wave = emotion === 'happy' ? Math.sin(t * 8) * 0.25 : 0;
    leftArm.current.rotation.z = -0.3 - s.arms + wave;
    rightArm.current.rotation.z = 0.3 + s.arms;

    const open = lipsync.level;
    mouth.current.scale.set(1 + s.smile * 0.25, 0.25 + open * 1.6 + Math.max(0, s.smile) * 0.2, 1);
    mouth.current.position.y = -0.09 + s.smile * 0.015;
    mouth.current.rotation.z = s.smile < 0 ? Math.PI : 0;

    browL.current.rotation.z = s.brow;
    browR.current.rotation.z = -s.brow;
    blush.current.visible = s.blush > 0.05;
    blush.current.children.forEach((m) => { m.material.opacity = s.blush * 0.6; });

    if (t > s.blinkAt) { eyes.current.scale.y = 0.1; if (t > s.blinkAt + 0.12) { eyes.current.scale.y = 1; s.blinkAt = t + 2 + Math.random() * 3; } }
    if (emotion === 'surprised') eyes.current.scale.set(1.3, eyes.current.scale.y, 1);
    else eyes.current.scale.x = 1;
  });

  return (
    <group ref={root}>
      {/* body */}
      <Limb from={[0, 1.35, 0]} length={0.6} color={c} thickness={0.045} />
      <Limb from={[0, 0.72, 0]} length={0.7} angle={0.18} color={c} />
      <Limb from={[0, 0.72, 0]} length={0.7} angle={-0.18} color={c} />
      <group ref={leftArm} position={[0, 1.28, 0]}><Limb length={0.55} color={c} /></group>
      <group ref={rightArm} position={[0, 1.28, 0]}><Limb length={0.55} color={c} /></group>

      {/* head */}
      <group ref={head} position={[0, 1.62, 0]}>
        <mesh><sphereGeometry args={[0.27, 32, 32]} /><meshStandardMaterial color="#fff6ee" /></mesh>
        <mesh scale={[1.04, 1.04, 1.04]}><sphereGeometry args={[0.27, 32, 32, 0, Math.PI * 2, 0, 1.1]} /><meshStandardMaterial color={c} /></mesh>
        <group ref={eyes} position={[0, 0.02, 0.25]}>
          <mesh position={[-0.09, 0, 0]}><sphereGeometry args={[0.032, 16, 16]} /><meshBasicMaterial color="#222" /></mesh>
          <mesh position={[0.09, 0, 0]}><sphereGeometry args={[0.032, 16, 16]} /><meshBasicMaterial color="#222" /></mesh>
        </group>
        <mesh ref={browL} position={[-0.09, 0.09, 0.255]}><boxGeometry args={[0.08, 0.012, 0.01]} /><meshBasicMaterial color="#333" /></mesh>
        <mesh ref={browR} position={[0.09, 0.09, 0.255]}><boxGeometry args={[0.08, 0.012, 0.01]} /><meshBasicMaterial color="#333" /></mesh>
        <mesh ref={mouth} position={[0, -0.09, 0.262]}>
          <circleGeometry args={[0.04, 24, Math.PI, Math.PI]} />
          <meshBasicMaterial color="#c0304a" side={2} />
        </mesh>
        <group ref={blush} position={[0, -0.04, 0.24]}>
          <mesh position={[-0.15, 0, 0]}><circleGeometry args={[0.035, 16]} /><meshBasicMaterial color="#ff7aa0" transparent opacity={0} /></mesh>
          <mesh position={[0.15, 0, 0]}><circleGeometry args={[0.035, 16]} /><meshBasicMaterial color="#ff7aa0" transparent opacity={0} /></mesh>
        </group>
      </group>
    </group>
  );
}
