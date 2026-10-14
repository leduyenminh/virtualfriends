import React, { useEffect, useRef } from 'react';
import * as PIXI from 'pixi.js';
import { install as installNoEval } from '@pixi/unsafe-eval';
import { lipsync } from '../../tts/speech.js';

window.PIXI = PIXI; // pixi-live2d-display looks for the global ticker
// PixiJS 6 compiles shaders with new Function(), which the app's Content-Security-Policy forbids. This patch
// swaps in eval-free implementations so Live2D works without weakening the CSP with 'unsafe-eval'.
installNoEval({ ShaderSystem: PIXI.ShaderSystem });

/** Expressions are matched by name in the model's .exp3.json list; first match wins. */
const EXPRESSION_HINTS = {
  happy: ['happy', 'smile', 'joy', 'f01'], sad: ['sad', 'cry', 'f03'], angry: ['angry', 'f02'],
  surprised: ['surprise', 'f04'], shy: ['shy', 'blush', 'f05'], neutral: ['normal', 'default', 'f00'],
};

/** Live2D Cubism 4 models. `url` is the model folder base URL; we look for <folder>/<anything>.model3.json via index. */
export default function Live2DAvatar({ url, emotion }) {
  const host = useRef(null);
  const modelRef = useRef(null);

  useEffect(() => {
    let app; let disposed = false;
    (async () => {
      const { Live2DModel } = await import('pixi-live2d-display/cubism4');
      app = new PIXI.Application({ backgroundAlpha: 0, resizeTo: host.current, antialias: true });
      host.current.appendChild(app.view);
      const modelUrl = url.endsWith('.model3.json') ? url : `${url}/model.model3.json`;
      const model = await Live2DModel.from(modelUrl, { autoInteract: false });
      if (disposed) { model.destroy(); return; }
      modelRef.current = model;
      const fit = () => {
        const scale = Math.min(app.screen.width / model.width, app.screen.height / model.height) * 0.95 * model.scale.x;
        model.scale.set(scale);
        model.x = (app.screen.width - model.width) / 2;
        model.y = app.screen.height - model.height;
      };
      app.stage.addChild(model);
      fit();
      app.renderer.on('resize', fit);
      // Drive the standard Cubism mouth parameter from our lip-sync signal every frame.
      model.internalModel.on('beforeModelUpdate', () => {
        model.internalModel.coreModel.setParameterValueById('ParamMouthOpenY', lipsync.level);
      });
    })().catch((e) => console.error('Live2D load failed', e));
    return () => { disposed = true; app?.destroy(true, { children: true }); modelRef.current = null; };
  }, [url]);

  useEffect(() => {
    const model = modelRef.current;
    const defs = model?.internalModel?.settings?.expressions;
    if (!defs?.length) return;
    const hints = EXPRESSION_HINTS[emotion] ?? [];
    const match = defs.find((d) => hints.some((h) => d.Name.toLowerCase().includes(h)));
    if (match) model.expression(match.Name);
  }, [emotion]);

  return <div ref={host} style={{ position: 'absolute', inset: 0 }} />;
}
