package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.dto.KnowledgeProjectRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectApplicationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectVO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProjectApplicationServiceTest {

    @Test
    void shouldCreateDefaultWorkBeforeInitialConversation() {
        KnowledgeProjectService projectService = mock(KnowledgeProjectService.class);
        KnowledgeProjectWorkService workService = mock(KnowledgeProjectWorkService.class);
        KnowledgeConversationService conversationService = mock(KnowledgeConversationService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        KnowledgeProjectRequest request = new KnowledgeProjectRequest();
        request.setName("都市脑洞");
        KnowledgeProjectVO project = new KnowledgeProjectVO();
        project.setProjectId(99L);
        project.setName("都市脑洞");
        when(projectService.create(request)).thenReturn(project);

        KnowledgeProjectApplicationService service = new KnowledgeProjectApplicationService(
            projectService,
            workService,
            conversationService,
            transactionManager
        );

        KnowledgeProjectVO created = service.create(request);

        assertThat(created).isSameAs(project);
        var ordered = inOrder(projectService, workService, conversationService);
        ordered.verify(projectService).create(request);
        ordered.verify(workService).ensureDefaultWork(99L, "都市脑洞");
        ordered.verify(conversationService).createInitialForProject(99L);
        verify(transactionManager).commit(transactionStatus);
    }
}
