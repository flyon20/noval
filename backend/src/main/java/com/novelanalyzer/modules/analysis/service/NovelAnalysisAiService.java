package com.novelanalyzer.modules.analysis.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiServices 声明式接口，由 AiServices.builder().chatModel(model).build() 自动生成代理。
 */
interface NovelAnalysisAiService {

    @SystemMessage("你是专业的网络小说分析师，请用中文输出结构化分析结果。")
    @UserMessage("{{prompt}}")
    String analyze(@V("prompt") String prompt);
}
