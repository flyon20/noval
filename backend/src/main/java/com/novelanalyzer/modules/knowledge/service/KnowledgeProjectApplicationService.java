package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.dto.KnowledgeProjectRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeProjectApplicationService {

    private final KnowledgeProjectService projectService;
    private final KnowledgeProjectWorkService workService;
    private final KnowledgeConversationService conversationService;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeProjectApplicationService(KnowledgeProjectService projectService,
                                              KnowledgeProjectWorkService workService,
                                              KnowledgeConversationService conversationService,
                                              PlatformTransactionManager transactionManager) {
        this.projectService = projectService;
        this.workService = workService;
        this.conversationService = conversationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public KnowledgeProjectVO create(KnowledgeProjectRequest request) {
        return transactionTemplate.execute(status -> {
            KnowledgeProjectVO project = projectService.create(request);
            workService.ensureDefaultWork(project.getProjectId(), project.getName());
            conversationService.createInitialForProject(project.getProjectId());
            return project;
        });
    }
}
