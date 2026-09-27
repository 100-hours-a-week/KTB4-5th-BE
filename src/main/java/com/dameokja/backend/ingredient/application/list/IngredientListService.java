package com.dameokja.backend.ingredient.application.list;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_CURSOR;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngredientListService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    // 다음 페이지가 있는지 알기 위해 요청 크기보다 한 건 더 읽는다.
    private static final int LOOKAHEAD = 1;

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;
    private final IngredientPageReader ingredientPageReader;
    private final IngredientListCursorCodec cursorCodec;
    private final Clock clock;

    public IngredientListResult getList(Long userId, Long refrigeratorId, IngredientSortType sortType,
                                        IngredientFilter filter, String cursorToken, int size) {
        Refrigerator refrigerator = refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        IngredientListCursor cursor = cursorOf(cursorToken, sortType, refrigeratorId, filter);
        List<Ingredient> rows = ingredientPageReader.read(cursor, size + LOOKAHEAD);
        List<Ingredient> page = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        String nextCursor = rows.size() > size ? cursorCodec.encode(cursor.after(page.getLast())) : null;
        long ingredientsNum = ingredientRepository.countByRefrigeratorId(refrigeratorId);
        return new IngredientListResult(page, cursor.baseDate(), ingredientsNum, countFiltered(cursor),
                refrigerator.getCapacity(), nextCursor);
    }

    // 커서의 기준일로 세야 스크롤 중 자정이 지나도 목록과 같은 조건으로 센다.
    private long countFiltered(IngredientListCursor cursor) {
        IngredientPageRange range = IngredientPageRange.of(cursor.filter(), cursor.baseDate());
        return ingredientRepository.countFiltered(cursor.refrigeratorId(), range.from(), range.to(), cursor.storageType());
    }

    private IngredientListCursor cursorOf(String cursorToken, IngredientSortType sortType, Long refrigeratorId,
                                          IngredientFilter filter) {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (cursorToken == null) {
            return IngredientListCursor.first(sortType, refrigeratorId, filter, today);
        }
        IngredientListCursor cursor = cursorCodec.decode(cursorToken);
        if (!cursor.matches(sortType, refrigeratorId, filter, today)) {
            throw new CustomException(INVALID_CURSOR);
        }
        return cursor;
    }
}
