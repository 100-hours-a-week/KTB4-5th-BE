package com.dameokja.backend.ingredient.presentation;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.ingredient.application.create.IngredientCreateService;
import com.dameokja.backend.ingredient.application.create.IngredientWriteResult;
import com.dameokja.backend.ingredient.application.detail.IngredientDetailService;
import com.dameokja.backend.ingredient.application.expire.IngredientExpireService;
import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateService;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IngredientCreateWebTest {
    private static final String PATH = "/api/v1/refrigerators/1/ingredients";
    private static final String ITEM = """
            {"name":"달걀","category":"TOFU_BEAN","storageType":"REFRIGERATED",
             "measureType":"COUNT","quantity":10,"weightValue":null,"weightUnit":"NONE",
             "expirationDate":"2026-10-20","imageUploadId":null}
            """;
    private final IngredientCreateService service = mock(IngredientCreateService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        IngredientController controller = new IngredientController(service, mock(IngredientDetailService.class),
                mock(IngredientUpdateService.class), mock(IngredientExpireService.class), mock(IngredientListService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void registersWithoutSourceAndAssignsDirectOnServer() throws Exception {
        when(service.create(isNull(), eq(1L), anyList())).thenReturn(new IngredientWriteResult(List.of(), 1, 0, 1, 100));

        mvc.perform(post(PATH).contentType("application/json").content("{\"items\":[" + ITEM + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("INGREDIENT-201-001"))
                .andExpect(jsonPath("$.data.createdCount").value(1));

        verify(service).create(isNull(), eq(1L), argThat(commands -> commands.size() == 1
                && commands.getFirst().registrationSource() == RegistrationSource.DIRECT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIRECT", "RECEIPT", "FOOD_IMAGE"})
    void rejectsClientSuppliedRegistrationSource(String source) throws Exception {
        String item = ITEM.strip().replace("\"imageUploadId\":null", "\"imageUploadId\":null,\"registrationSource\":\"" + source + "\"");

        mvc.perform(post(PATH).contentType("application/json").content("{\"items\":[" + item + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"));

        verifyNoInteractions(service);
    }
}
