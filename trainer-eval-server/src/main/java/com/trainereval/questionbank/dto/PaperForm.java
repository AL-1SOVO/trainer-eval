package com.trainereval.questionbank.dto;

import lombok.Data;
import java.util.List;

@Data
public class PaperForm {
    private Long courseId;
    private String paperName;
    private String description;
    private Integer totalScore;
    private Integer passScore;
    private Integer duration;
    private Integer shuffleQuestions;
    private Integer shuffleOptions;
    private Integer allowRetake;
    private Integer maxAttempts;
    private Integer status;
    private List<PaperQuestionItem> questions;
}
