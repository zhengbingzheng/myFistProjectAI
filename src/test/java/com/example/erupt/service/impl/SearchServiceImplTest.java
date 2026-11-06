package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.RequestScopeCache;
import com.example.erupt.repository.mapper.SearchMapper;
import com.example.erupt.service.DataScopeService;
import com.example.erupt.service.MenuService;
import com.example.erupt.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private SearchMapper searchMapper;

    @Mock
    private DataScopeService dataScope;

    @Mock
    private MenuService menuService;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        RequestScopeCache.clear();
    }

    @Test
    @DisplayName("没有对应菜单时不查该模块")
    void skipsModulesTheUserCannotOpen() {
        AuthContext.set(new TokenService.TokenIdentity("u1", "someone", Long.MAX_VALUE));
        when(menuService.hasPermission("u1", "knowledge")).thenReturn(true);
        when(menuService.hasPermission("u1", "navigation")).thenReturn(false);
        when(menuService.hasPermission("u1", "attachments")).thenReturn(false);
        when(menuService.hasPermission("u1", "gantt")).thenReturn(false);
        when(searchMapper.searchKnowledge(anyString(), eq(5))).thenReturn(List.of());

        new SearchServiceImpl(searchMapper, dataScope, menuService, false).search("知识", 5);

        verify(searchMapper).searchKnowledge(anyString(), eq(5));
        verify(searchMapper, never()).searchNavigations(anyString(), anyInt());
        verify(searchMapper, never()).searchAttachments(anyString(), anyInt());
        verify(searchMapper, never()).searchPlans(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("全文开关打开且关键词够长时走 MATCH")
    void usesFullTextWhenEnabled() {
        AuthContext.set(new TokenService.TokenIdentity("u1", "someone", Long.MAX_VALUE));
        when(menuService.hasPermission("u1", "knowledge")).thenReturn(true);
        when(menuService.hasPermission("u1", "navigation")).thenReturn(false);
        when(menuService.hasPermission("u1", "attachments")).thenReturn(false);
        when(menuService.hasPermission("u1", "gantt")).thenReturn(false);
        when(searchMapper.searchKnowledgeFullText("知识库", 5)).thenReturn(List.of());

        new SearchServiceImpl(searchMapper, dataScope, menuService, true).search("知识库", 5);

        verify(searchMapper).searchKnowledgeFullText("知识库", 5);
        verify(searchMapper, never()).searchKnowledge(anyString(), anyInt());
    }
}
