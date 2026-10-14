import React, { Suspense, lazy } from 'react';
import { Canvas } from '@react-three/fiber';
import { OrbitControls } from '@react-three/drei';
import StickFigure from './StickFigure.jsx';
import VrmAvatar from './VrmAvatar.jsx';
import GltfAvatar from './GltfAvatar.jsx';
import UnityAvatar from './UnityAvatar.jsx';

const Live2DAvatar = lazy(() => import('./Live2DAvatar.jsx')); // pulls in pixi.js only when needed

/** Picks a renderer from the avatar's format: PLACEHOLDER (stick figure), VRM, GLTF, LIVE2D or UNITY. */
export default function AvatarStage({ avatar, emotion, speaking }) {
  const format = avatar?.format ?? 'PLACEHOLDER';

  if (format === 'LIVE2D') {
    return <Suspense fallback={null}><Live2DAvatar url={avatar.url} emotion={emotion} /></Suspense>;
  }
  if (format === 'UNITY') return <UnityAvatar url={avatar.url} emotion={emotion} />;

  return (
    <Canvas camera={{ position: [0, 1.1, 4.3], fov: 35 }} dpr={[1, 2]}>
      <ambientLight intensity={0.8} />
      <directionalLight position={[2, 4, 3]} intensity={1.2} />
      <Suspense fallback={null}>
        {format === 'VRM' && <VrmAvatar url={avatar.url} emotion={emotion} />}
        {format === 'GLTF' && <GltfAvatar url={avatar.url} emotion={emotion} />}
        {format === 'PLACEHOLDER' && <StickFigure color={(avatar?.url ?? 'stick:pink').split(':')[1]} emotion={emotion} speaking={speaking} />}
      </Suspense>
      <OrbitControls target={[0, 1, 0]} enablePan={false} minDistance={1.5} maxDistance={6} />
    </Canvas>
  );
}
