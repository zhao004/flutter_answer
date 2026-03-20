package com.example.flutter_answer.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object OpenAiResponsesClient {

    fun createResponse(
        providerUrl: String,
        apiKey: String,
        model: String,
        instructions: String,
        tools: JSONArray,
        input: JSONArray,
    ): JSONObject {
        val endpoint = normalizeEndpoint(providerUrl)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")

        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("tools", tools)
            .put("input", input)

        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw IllegalStateException(
                    "OpenAI Responses API request failed ($responseCode): ${text.take(500)}",
                )
            }

            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeEndpoint(providerUrl: String): String {
        val normalized = providerUrl.trim().removeSuffix("/")
        return when {
            normalized.endsWith("/responses") -> normalized
            normalized.endsWith("/v1") -> "$normalized/responses"
            else -> "$normalized/v1/responses"
        }
    }
}
