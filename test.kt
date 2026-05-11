import com.google.mediapipe.tasks.genai.llminference.LlmInference
import android.content.Context

fun test(inference: LlmInference) {
    inference.generateResponseAsync("hello") { partial, done -> }
}
