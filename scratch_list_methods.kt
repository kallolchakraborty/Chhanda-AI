import kotlin.reflect.full.memberFunctions
fun main() {
    val clazz = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession")
    clazz.declaredMethods.forEach { println(it.name) }
}
