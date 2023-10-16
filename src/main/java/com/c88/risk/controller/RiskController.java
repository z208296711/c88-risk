package com.c88.risk.controller;

import com.c88.common.core.result.Result;
import com.c88.common.core.vo.BetRecordVo;
import lombok.AllArgsConstructor;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.c88.risk.contant.StoreConstants.*;

@RestController
@AllArgsConstructor
public class RiskController {

    private final InteractiveQueryService interactiveQueryService;

    @GetMapping("/betRecord/{username}/{fromBeginning}")
    public Result<BetRecordVo> betRecord(@PathVariable String username, @PathVariable boolean fromBeginning) {
        final ReadOnlyKeyValueStore<String, BetRecordVo> betRecordStore =
                interactiveQueryService.getQueryableStore(fromBeginning ? AGG_BET_RECORD : AGG_BET_RECORD_FROM_LAST_WITHDRAW, QueryableStoreTypes.keyValueStore());
        return Result.success(betRecordStore.get(username));
    }

    @GetMapping("/betRecord/monthly/{username}")
    public Result<BetRecordVo> monthlyBetRecord(@PathVariable String username) {
        final ReadOnlyKeyValueStore<String, BetRecordVo> betRecordStore =
                interactiveQueryService.getQueryableStore(MONTHLY_AGG_BET_RECORD, QueryableStoreTypes.keyValueStore());
        return Result.success(betRecordStore.get(username));
    }

    @GetMapping("/betRecord/daily")
    public Result<Map<String,BetRecordVo>> yesterdayBetRecord(){
        final ReadOnlyKeyValueStore<String, BetRecordVo> betRecordStore =
                interactiveQueryService.getQueryableStore(DAILY_AGG_BET_RECORD, QueryableStoreTypes.keyValueStore());
        KeyValueIterator<String, BetRecordVo> all = betRecordStore.all();
        Map<String, BetRecordVo> map = new HashMap<>();
        while (all.hasNext()) {
            KeyValue<String, BetRecordVo> next = all.next();
            String username = next.key;
            BetRecordVo betRecordVo = next.value;

            map.put(username, betRecordVo);
        }
        return Result.success(map);
    }


}
