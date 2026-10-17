package com.dameokja.backend.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.presentation.request.AnalysisCallbackRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AiImageAnalysisResponseTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    // AI 4a03494 조회 응답과, 같은 분석을 AI build_callback_payload로 바꾼 콜백 본문이다.
    private static final String AI_VIEW = """
            {
              "analysisId": "ana_1",
              "status": "COMPLETED",
              "stage": "COMPLETED",
              "submittedAt": "2026-10-17T03:00:00Z",
              "startedAt": "2026-10-17T03:00:01Z",
              "completedAt": "2026-10-17T03:00:05Z",
              "result": {
                "documentType": {
                  "value": "RECEIPT",
                  "confidence": 0.97
                },
                "imageQuality": {
                  "score": 0.9,
                  "issues": []
                },
                "items": [
                  {
                    "itemId": "item-1",
                    "displayStatus": "RECOGNIZED",
                    "name": {
                      "value": "서울우유",
                      "normalizedValue": "우유",
                      "source": "OCR",
                      "confidence": 0.9,
                      "evidenceText": "서울우유"
                    },
                    "category": {
                      "value": "DAIRY",
                      "source": "CATALOG",
                      "confidence": 0.9
                    },
                    "storageType": "REFRIGERATED",
                    "measureType": "COUNT",
                    "quantity": {
                      "value": 2,
                      "source": "RECEIPT_LINE",
                      "confidence": 0.9
                    },
                    "weight": {
                      "value": null,
                      "unit": "NONE",
                      "source": "BUSINESS_RULE",
                      "confidence": 1.0
                    },
                    "expiration": {
                      "date": "2026-10-24",
                      "dateType": "USE_BY",
                      "source": "OCR",
                      "confidence": 0.8,
                      "evidenceText": "10.24",
                      "estimatedRange": null,
                      "assumptions": [],
                      "evidenceIds": []
                    },
                    "reviewReasons": []
                  },
                  {
                    "itemId": "item-2",
                    "displayStatus": "AI_ESTIMATED",
                    "name": {
                      "value": "",
                      "normalizedValue": "계란",
                      "source": "VISION",
                      "confidence": 0.7
                    },
                    "category": {
                      "value": "OTHER",
                      "source": "MODEL_INFERENCE",
                      "confidence": 0.6
                    },
                    "storageType": "REFRIGERATED",
                    "measureType": "COUNT",
                    "quantity": {
                      "value": 1,
                      "source": "BUSINESS_RULE",
                      "confidence": 0.5
                    },
                    "weight": {
                      "value": null,
                      "unit": "NONE",
                      "source": "BUSINESS_RULE",
                      "confidence": 1.0
                    },
                    "expiration": {
                      "date": null,
                      "dateType": "ESTIMATED_CONSUMPTION_WINDOW",
                      "estimatedRange": {
                        "from": "2026-10-20",
                        "to": "2026-10-30"
                      },
                      "assumptions": [
                        "냉장"
                      ],
                      "evidenceIds": []
                    },
                    "reviewReasons": [
                      {
                        "field": "QUANTITY",
                        "code": "QUANTITY_DEFAULTED",
                        "messages": [
                          "수량을 확인해 주세요."
                        ]
                      }
                    ]
                  },
                  {
                    "itemId": "item-3",
                    "displayStatus": "RECOGNIZED",
                    "name": {
                      "value": "삼겹살",
                      "source": "RECEIPT_LINE",
                      "confidence": 0.9
                    },
                    "category": {
                      "value": "MEAT",
                      "source": "CATALOG",
                      "confidence": 0.9
                    },
                    "storageType": "FROZEN",
                    "measureType": "WEIGHT",
                    "quantity": null,
                    "weight": {
                      "value": 0,
                      "unit": "G",
                      "source": "OCR",
                      "confidence": 0.4
                    },
                    "expiration": {
                      "date": null,
                      "dateType": "UNKNOWN",
                      "estimatedRange": null,
                      "assumptions": [],
                      "evidenceIds": []
                    },
                    "reviewReasons": []
                  },
                  {
                    "itemId": "item-4",
                    "displayStatus": "RECOGNIZED",
                    "name": {
                      "value": "올리브유",
                      "source": "OCR",
                      "confidence": 0.9
                    },
                    "category": {
                      "value": "SEASONING",
                      "source": "CATALOG",
                      "confidence": 0.9
                    },
                    "storageType": "REFRIGERATED",
                    "measureType": "WEIGHT",
                    "quantity": null,
                    "weight": {
                      "value": 500.5,
                      "unit": "ML",
                      "source": "OCR",
                      "confidence": 0.9
                    },
                    "expiration": {
                      "date": null,
                      "dateType": "UNKNOWN",
                      "estimatedRange": null,
                      "assumptions": [],
                      "evidenceIds": []
                    },
                    "reviewReasons": []
                  }
                ],
                "modelTrace": {
                  "pipelineVersion": "p",
                  "classifierVersion": "c",
                  "ocrVersion": "o",
                  "llmModel": "l",
                  "normalizerVersion": "n",
                  "policyVersion": "y"
                }
              },
              "error": null
            }
            """;
    private static final String AI_CALLBACK = """
            {
              "analysisId": "ana_1",
              "status": "COMPLETED",
              "completedAt": "2026-10-17T03:00:05Z",
              "result": {
                "documentType": "RECEIPT",
                "items": [
                  {
                    "itemId": "item-1",
                    "displayStatus": "RECOGNIZED",
                    "name": "서울우유",
                    "category": "DAIRY",
                    "storageType": "REFRIGERATED",
                    "measureType": "COUNT",
                    "quantity": 2,
                    "weight": {
                      "value": null,
                      "unit": "NONE"
                    },
                    "expiration": {
                      "date": "2026-10-24",
                      "dateType": "USE_BY",
                      "estimatedRange": null
                    },
                    "reviewReasons": []
                  },
                  {
                    "itemId": "item-2",
                    "displayStatus": "NEEDS_REVIEW",
                    "name": "계란",
                    "category": "OTHER",
                    "storageType": "REFRIGERATED",
                    "measureType": "COUNT",
                    "quantity": null,
                    "weight": {
                      "value": null,
                      "unit": "NONE"
                    },
                    "expiration": {
                      "date": null,
                      "dateType": "ESTIMATED_CONSUMPTION_WINDOW",
                      "estimatedRange": {
                        "from": "2026-10-20",
                        "to": "2026-10-30"
                      }
                    },
                    "reviewReasons": [
                      {
                        "field": "QUANTITY",
                        "code": "QUANTITY_DEFAULTED",
                        "messages": [
                          "수량을 확인해 주세요."
                        ]
                      }
                    ]
                  },
                  {
                    "itemId": "item-3",
                    "displayStatus": "NEEDS_REVIEW",
                    "name": "삼겹살",
                    "category": "MEAT",
                    "storageType": "FROZEN",
                    "measureType": "WEIGHT",
                    "quantity": null,
                    "weight": {
                      "value": null,
                      "unit": null
                    },
                    "expiration": {
                      "date": null,
                      "dateType": "UNKNOWN",
                      "estimatedRange": null
                    },
                    "reviewReasons": []
                  },
                  {
                    "itemId": "item-4",
                    "displayStatus": "RECOGNIZED",
                    "name": "올리브유",
                    "category": "SEASONING",
                    "storageType": "REFRIGERATED",
                    "measureType": "WEIGHT",
                    "quantity": null,
                    "weight": {
                      "value": 500.5,
                      "unit": "ML"
                    },
                    "expiration": {
                      "date": null,
                      "dateType": "UNKNOWN",
                      "estimatedRange": null
                    },
                    "reviewReasons": []
                  }
                ]
              },
              "error": null
            }
            """;

    @Test
    void convertsTheLookupResultTheSameWayAiBuildsTheCallback() {
        AnalysisCompletion recovered = mapper.readValue(AI_VIEW, AiImageAnalysisResponse.class).toCompletion();
        AnalysisCompletion callback = mapper.readValue(AI_CALLBACK, AnalysisCallbackRequest.class).completion();
        assertThat(recovered).isEqualTo(callback);
    }

    @Test
    void convertsAFailedLookupIntoAFailedCompletion() {
        AiImageAnalysisResponse response = mapper.readValue("""
                {"analysisId":"ana_2","status":"FAILED","submittedAt":"2026-10-17T03:00:00Z","completedAt":"2026-10-17T03:00:05Z",
                 "result":null,"error":{"code":"UNSUPPORTED_IMAGE","message":"지원하지 않는 이미지","retryable":false,"retryAfterMs":null}}
                """, AiImageAnalysisResponse.class);
        AnalysisCompletion completion = response.toCompletion();
        assertThat(response.isFinished()).isTrue();
        assertThat(completion.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(completion.error().code()).isEqualTo("UNSUPPORTED_IMAGE");
        assertThat(completion.error().retryable()).isFalse();
    }

    @Test
    void treatsQueuedAndProcessingAsNotFinished() {
        assertThat(new AiImageAnalysisResponse("a", "QUEUED", null, null, null, null, null, null).isFinished()).isFalse();
        assertThat(new AiImageAnalysisResponse("a", "PROCESSING", null, null, null, null, null, null).isFinished()).isFalse();
    }
}
