# Unity avatar bridge

Use Unity when you want to do more than VRM, Live2D or glTF can: custom shaders, physics, particle effects, stage scenes.

1. Copy `Assets/` into your Unity project (2022.3 LTS or newer).
2. Create an empty GameObject named **`AvatarBridge`** and add `AvatarBridge.cs`. Assign the face `SkinnedMeshRenderer`
   and, optionally, an `Animator` with triggers `neutral, happy, sad, angry, surprised, shy`.
3. Go to *Player Settings → WebGL → Resolution and Presentation* and pick the **VChatBridge** template.
4. Go to *Player Settings → WebGL → Publishing Settings* and set **Compression Format = Disabled**. The backend and object
   storage then serve the files without special `Content-Encoding` headers.
5. Build for WebGL, then in the app open **AI friend → ⚙️ → Import model… → Unity WebGL** and pick the build folder.

The build runs in a sandboxed `<iframe>` with an opaque origin, so it cannot read the app's login token or DOM.
The app sends only two messages: `SetEmotion(string)` and `SetMouth("0..1")`.
