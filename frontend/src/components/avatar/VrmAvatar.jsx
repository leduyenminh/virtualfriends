import React, { useEffect, useState } from 'react';
import { useFrame } from '@react-three/fiber';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';
import { lipsync } from '../../tts/speech.js';

// VRM 1.0 preset expression names (VRM 0.x models are mapped by three-vrm).
const EXPR = { happy: 'happy', sad: 'sad', angry: 'angry', surprised: 'surprised', shy: 'relaxed', neutral: null };

/** VRM avatars: what VRoid Studio and Blender's VRM add-on export; the de-facto 3D VTuber format. */
export default function VrmAvatar({ url, emotion }) {
  const [vrm, setVrm] = useState(null);

  useEffect(() => {
    let disposed = false;
    const loader = new GLTFLoader();
    loader.register((parser) => new VRMLoaderPlugin(parser));
    loader.load(url, (gltf) => {
      const v = gltf.userData.vrm;
      VRMUtils.removeUnnecessaryVertices(gltf.scene);
      VRMUtils.rotateVRM0(v);
      // relax T-pose arms
      v.humanoid?.getNormalizedBoneNode('leftUpperArm')?.rotation.set(0, 0, 1.2);
      v.humanoid?.getNormalizedBoneNode('rightUpperArm')?.rotation.set(0, 0, -1.2);
      if (!disposed) setVrm(v); else VRMUtils.deepDispose(v.scene);
    });
    return () => { disposed = true; };
  }, [url]);

  useEffect(() => () => vrm && VRMUtils.deepDispose(vrm.scene), [vrm]);

  useFrame(({ clock }, dt) => {
    if (!vrm) return;
    const em = vrm.expressionManager;
    if (em) {
      for (const name of Object.values(EXPR)) if (name) em.setValue(name, name === EXPR[emotion] ? 1 : 0);
      em.setValue('aa', lipsync.level);
      const t = clock.elapsedTime % 4;
      em.setValue('blink', t > 3.85 ? 1 : 0);
    }
    const spine = vrm.humanoid?.getNormalizedBoneNode('spine');
    if (spine) spine.rotation.z = Math.sin(clock.elapsedTime * 1.2) * 0.03;
    vrm.update(dt);
  });

  return vrm ? <primitive object={vrm.scene} /> : null;
}
