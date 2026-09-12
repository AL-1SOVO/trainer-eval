package com.trainereval.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.config.OpenAIConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAIClient {

    private final OpenAIConfig openAIConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> chatCompletion(String systemPrompt, String userPrompt) {
        return chatCompletion(systemPrompt, userPrompt,
                openAIConfig.getModel(), openAIConfig.getTemperature(), openAIConfig.getMaxTokens());
    }

    public Map<String, Object> chatCompletion(String systemPrompt, String userPrompt,
                                               String model, Double temperature, Integer maxTokens) {
        Map<String, Object> result = new HashMap<>();

        try {
            String url = openAIConfig.getBaseUrl() + "/v1/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAIConfig.getApiKey());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model != null ? model : openAIConfig.getModel());

            List<Map<String, String>> messages = new ArrayList<>();

            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);

            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature != null ? temperature : 0.3);
            requestBody.put("max_tokens", maxTokens != null ? maxTokens : 4096);

            Map<String, String> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                result.put("content", "");
                result.put("promptTokens", 0);
                result.put("completionTokens", 0);
                result.put("totalTokens", 0);
                return result;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices != null && !choices.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    String content = (String) message.get("content");
                    if ((content == null || content.isBlank()) && message.get("reasoning_content") != null) {
                        content = String.valueOf(message.get("reasoning_content"));
                    }
                    result.put("content", content != null ? content : "");
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) responseBody.get("usage");
            if (usage != null) {
                result.put("promptTokens", usage.getOrDefault("prompt_tokens", 0));
                result.put("completionTokens", usage.getOrDefault("completion_tokens", 0));
                result.put("totalTokens", usage.getOrDefault("total_tokens", 0));
            } else {
                result.put("promptTokens", 0);
                result.put("completionTokens", 0);
                result.put("totalTokens", 0);
            }

            if (!result.containsKey("content")) {
                result.put("content", "");
            }

        } catch (RestClientException e) {
            log.error("OpenAI API call failed: {}", e.getMessage(), e);
            result.put("content", "");
            result.put("promptTokens", 0);
            result.put("completionTokens", 0);
            result.put("totalTokens", 0);
            result.put("error", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during OpenAI API call: {}", e.getMessage(), e);
            result.put("content", "");
            result.put("promptTokens", 0);
            result.put("completionTokens", 0);
            result.put("totalTokens", 0);
            result.put("error", e.getMessage());
        }

        return result;
    }
}
