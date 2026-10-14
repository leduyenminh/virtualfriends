import React, { useEffect, useMemo } from 'react';
import { useFrame } from '@react-three/fiber';
import { useGLTF, useAnimations, Center } from '@react-three/drei';
import { lipsync } from '../../tts/speech.js';

/**
 * Plain glTF/GLB (e.g. exported from Blender). If the mesh has shape keys named like common VTuber blendshapes
 * ("mouthOpen", "jawOpen", "A", "aa", "happy", "smile", "sad", "angry", "surprised"), they are driven automatically.
 * Embedded animations named "idle" (or the first clip) loop.
 */
export default function GltfAvatar({ url, emotion }) {
  const { scene, animations } = useGLTF(url);
  const { actions, names } = useAnimations(animations, scene);

  const morphMeshes = useMemo(() => {
    const list = [];
    scene.traverse((o) => { if (o.morphTargetDictionary) list.push(o); });
    return list;
  }, [scene]);

  useEffect(() => {
    const idle = actions[names.find((n) => /idle/i.test(n)) ?? names[0]];
    idle?.reset().fadeIn(0.3).play();
    return () => idle?.fadeOut(0.3);
  }, [actions, names]);

  useFrame(() => {
    for (const m of morphMeshes) {
      const d = m.morphTargetDictionary;
      const set = (keys, v) => keys.forEach((k) => { if (d[k] !== undefined) m.morphTargetInfluences[d[k]] = v; });
      set(['mouthOpen', 'jawOpen', 'A', 'aa', 'Fcl_MTH_A'], lipsync.level);
      set(['happy', 'smile', 'Joy', 'Fcl_ALL_Joy'], emotion === 'happy' || emotion === 'shy' ? 1 : 0);
      set(['sad', 'Sorrow', 'Fcl_ALL_Sorrow'], emotion === 'sad' ? 1 : 0);
      set(['angry', 'Angry', 'Fcl_ALL_Angry'], emotion === 'angry' ? 1 : 0);
      set(['surprised', 'Surprised', 'Fcl_ALL_Surprised'], emotion === 'surprised' ? 1 : 0);
    }
  });

  return <Center bottom><primitive object={scene} /></Center>;
}
