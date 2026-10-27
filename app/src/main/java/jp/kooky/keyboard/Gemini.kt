package jp.kooky.keyboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Gemini API で音声入力の文章をビジネス敬語に添削する（通信するので別スレッドで呼ぶこと）
object Gemini {

    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private const val PROMPT =
        "あなたは日本語の文章校正者です。ユーザーの文章は音声入力で書き起こしたもので、" +
            "言いよどみ・誤変換・句読点の欠落を含みます。" +
            "これを社内連絡やお客様への連絡にそのまま使える、自然で丁寧なビジネス敬語の文章に整えてください。\n" +
            "・「えー」「あの」などのフィラー、言い直し、重複を取り除く\n" +
            "・句読点と改行を適切に補う\n" +
            "・誤変換は文脈から正しい語に直す\n" +
            "・内容、事実、日時、数字、固有名詞は変えない。情報を付け足さない\n" +
            "・出力は整えた本文だけ。前置き、説明、引用符、箇条書きの見出しは付けない"

    fun correct(apiKey: String, model: String, text: String): String {
        return try {
            request(apiKey, model, text, withThinking = true)
        } catch (e: HttpError) {
            // 古いモデルなど thinkingConfig 非対応のときは外してもう一度
            if (e.code == 400) request(apiKey, model, text, withThinking = false) else throw e
        }
    }

    class HttpError(val code: Int, message: String) : IOException(message)

    private fun request(apiKey: String, model: String, text: String, withThinking: Boolean): String {
        val m = model.trim().ifEmpty { DEFAULT_MODEL }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 10_000
            con.readTimeout = 30_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", apiKey.trim())

            val gen = JSONObject()
                .put("temperature", 0.3)
                .put("maxOutputTokens", 2048)
            if (withThinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))

            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PROMPT)))
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                )
                .put("generationConfig", gen)

            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, errorMessage(code, resp))

            val parts = JSONObject(resp)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("AIから文章が返ってこなかったよ")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw IOException("AIから文章が返ってこなかったよ")
            return out
        } finally {
            con.disconnect()
        }
    }

    private fun errorMessage(code: Int, resp: String): String {
        val detail = try {
            JSONObject(resp).optJSONObject("error")?.optString("message", "") ?: ""
        } catch (e: Exception) {
            ""
        }.take(120)
        val head = when (code) {
            400 -> "リクエストエラー（モデル名を確認してね）"
            401, 403 -> "APIキーが正しくないかも"
            404 -> "モデルが見つからないよ（モデル名を確認してね）"
            429 -> "利用上限に達したかも。少し待ってから試してね"
            else -> "AIの通信エラー（$code）"
        }
        return if (detail.isNotEmpty()) "$head\n$detail" else head
    }
}
