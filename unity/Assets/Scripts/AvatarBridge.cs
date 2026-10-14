using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// Receives commands from the VirtualFriends web app (via the VChatBridge WebGL template, which calls
/// SendMessage("AvatarBridge", method, value)). Put this on a GameObject named exactly "AvatarBridge".
///
/// Drives:
///  - mouth: a blendshape (e.g. "A", "aa", "MouthOpen") on the face mesh, 0..1
///  - emotion: an Animator trigger with the emotion name (happy, sad, angry, surprised, shy, neutral)
///             and/or a blendshape per emotion.
/// </summary>
public class AvatarBridge : MonoBehaviour
{
    [SerializeField] private SkinnedMeshRenderer face;
    [SerializeField] private Animator animator;
    [SerializeField] private string mouthBlendShape = "A";
    [SerializeField] private float mouthSmoothing = 18f;

    [System.Serializable]
    public struct EmotionShape { public string emotion; public string blendShape; }
    [SerializeField] private List<EmotionShape> emotionShapes = new List<EmotionShape>
    {
        new EmotionShape { emotion = "happy", blendShape = "Joy" },
        new EmotionShape { emotion = "sad", blendShape = "Sorrow" },
        new EmotionShape { emotion = "angry", blendShape = "Angry" },
        new EmotionShape { emotion = "surprised", blendShape = "Surprised" },
        new EmotionShape { emotion = "shy", blendShape = "Joy" },
    };

    private float targetMouth;
    private float mouth;
    private string emotion = "neutral";

    // Called from JavaScript; SendMessage only passes strings/numbers, so parse here.
    public void SetMouth(string value)
    {
        if (float.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var v))
            targetMouth = Mathf.Clamp01(v);
    }

    public void SetEmotion(string value)
    {
        emotion = string.IsNullOrEmpty(value) ? "neutral" : value.ToLowerInvariant();
        if (animator != null) animator.SetTrigger(emotion);
    }

    private void Update()
    {
        if (face == null) return;
        mouth = Mathf.Lerp(mouth, targetMouth, 1f - Mathf.Exp(-mouthSmoothing * Time.deltaTime));
        SetShape(mouthBlendShape, mouth);

        foreach (var es in emotionShapes)
        {
            int i = face.sharedMesh.GetBlendShapeIndex(es.blendShape);
            if (i < 0) continue;
            float goal = es.emotion == emotion ? 100f : 0f;
            face.SetBlendShapeWeight(i, Mathf.MoveTowards(face.GetBlendShapeWeight(i), goal, 400f * Time.deltaTime));
        }
    }

    private void SetShape(string name, float weight01)
    {
        int i = face.sharedMesh.GetBlendShapeIndex(name);
        if (i >= 0) face.SetBlendShapeWeight(i, weight01 * 100f);
    }
}
