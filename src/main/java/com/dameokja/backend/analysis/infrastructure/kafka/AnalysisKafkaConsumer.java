package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisKafkaConsumer {
    private final AnalysisCompletionPersistence analysisCompletionPersistence;

    @KafkaListener(id = "analysis-completed", idIsGroup = false, topics = "${analysis.kafka.completion-topic}")
    public void consume(ConsumerRecord<String, AnalysisCompletedEvent> consumerRecord) {
        AnalysisCompletedEvent analysisCompletedEvent = consumerRecord.value();
        if (analysisCompletedEvent == null || analysisCompletedEvent.payload() == null || analysisCompletedEvent.payload().userId() == null
                || !analysisCompletedEvent.payload().userId().toString().equals(consumerRecord.key())) {
            throw new IllegalArgumentException("완료 이벤트의 key는 payload의 userId와 같아야 합니다.");
        }
        try {
            // 서비스 프록시의 DB 커밋이 끝난 뒤 반환해야 RECORD 모드에서 offset을 넘길 수 있다.
            analysisCompletionPersistence.apply(analysisCompletedEvent);
        } catch (CustomException exception) {
            if (exception.getExceptionCode() == AnalysisExceptionCode.NOT_FOUND || exception.getExceptionCode() == AnalysisExceptionCode.RESULT_CONFLICT) {
                throw new IllegalArgumentException("적용할 수 없는 분석 완료 이벤트입니다.", exception);
            }
            throw exception;
        }
    }
}
