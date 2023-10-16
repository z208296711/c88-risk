package com.c88.risk.streams;

import com.c88.admin.api.RiskConfigFeignClient;
import com.c88.admin.dto.RiskConfigDTO;
import com.c88.common.core.vo.BetRecordVo;
import com.c88.game.adapter.enums.BetOrderEventTypeEnum;
import com.c88.game.adapter.event.BetRecord;
import com.c88.payment.vo.WithdrawVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.c88.common.core.constant.TopicConstants.RISK_CONFIG;
import static com.c88.risk.contant.StoreConstants.AGG_BET_RECORD;
import static com.c88.risk.contant.StoreConstants.AGG_BET_RECORD_FROM_LAST_WITHDRAW;
import static com.c88.risk.contant.StoreConstants.DAILY_AGG_BET_RECORD;
import static com.c88.risk.contant.StoreConstants.MONTHLY_AGG_BET_RECORD;
import static java.math.BigDecimal.ZERO;

/**
 * 與注單相關的 streams topology
 */
@Configuration
@Slf4j
@AllArgsConstructor
public class BetRecordStreams {

    private final RiskConfigFeignClient riskConfigFeignClient;

    private static final ObjectMapper mapper = new ObjectMapper();

    private static RiskConfigDTO localRiskConfig;

    private int getRewardBet() {
        // 沒有會員的風控設定時取得設定
        if (localRiskConfig == null) {
            localRiskConfig = riskConfigFeignClient.getRiskConfigByType(0).getData();
        }
        return localRiskConfig.getRewardBet();
    }

    @KafkaListener(id = "#{T(java.util.UUID).randomUUID().toString()}", topics = RISK_CONFIG)
    public void listen(ConsumerRecord<String, RiskConfigDTO> record, Acknowledgment acknowledgment) {
        localRiskConfig = record.value();
        acknowledgment.acknowledge();
    }

    @Bean
    public Consumer<KStream<String, BetRecord>> betRecordMonthly() {
        LocalDateTime fromTime = LocalDateTime.of(LocalDate.now(ZoneId.of("+7")).with(TemporalAdjusters.firstDayOfMonth()), LocalTime.MIN);
        LocalDateTime toTime = LocalDateTime.of(LocalDate.now(ZoneId.of("+7")).with(TemporalAdjusters.lastDayOfMonth()), LocalTime.MAX);

        Serde<BetRecordVo> betRecordVoSerde = new JsonSerde<>(BetRecordVo.class, mapper);
        return (betRecord) -> betRecord
                .filter((key, value) -> {
                    if (value.getSettleTime() == null) {
                        return false;
                    }
                    if (!value.getSettleTime().isAfter(fromTime) || !value.getSettleTime().isBefore(toTime)) {
                        return false;
                    }
                    return true;
                })
                .groupByKey()
                .aggregate(
                        BetRecordVo::new,
                        (key, newBetRecord, aggBetRecord) -> {
                            if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_SETTLED.getValue())) {
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().add(newBetRecord.getValidBetAmount()));
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newBetRecord.getSettle()));
                                aggBetRecord.setWinLoss(aggBetRecord.getWinLoss().add(newBetRecord.getWinLoss()));
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_UPDATE_SETTLE.getValue()) &&
                                    newBetRecord.getSettleDiff() != null) {// 狀態有變注單
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newBetRecord.getSettleDiff()));// 加總差額
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_CANCELED.getValue())) {
                                aggBetRecord.setSettle(aggBetRecord.getSettle().subtract(newBetRecord.getSettle()));// 減去之前的派彩
                                aggBetRecord.setWinLoss(aggBetRecord.getWinLoss().subtract(newBetRecord.getWinLoss()));// 減去之前的輸贏
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().subtract(newBetRecord.getValidBetAmount()));// 減去之前的有效投注
                            }
                            if (aggBetRecord.getValidBetAmount().compareTo(ZERO) != 0 &&
                                    aggBetRecord.getSettle().divide(aggBetRecord.getValidBetAmount(), 5, RoundingMode.HALF_UP).doubleValue() >= getRewardBet()) {
                                log.warn("RewardBet reached!");
                            }
                            return aggBetRecord;
                        },
                        Materialized.<String, BetRecordVo, KeyValueStore<Bytes, byte[]>>as(MONTHLY_AGG_BET_RECORD)
                                .withKeySerde(Serdes.String()).
                                withValueSerde(betRecordVoSerde)
                );
    }

    @Bean
    public Consumer<KStream<String, BetRecord>> betRecordYesterday() {
        LocalDate now = LocalDate.now(ZoneId.of("+7"));

        LocalDateTime fromTime = now.atStartOfDay(); //LocalDateTime.of(LocalDate.now(ZoneId.of("+7")).with(TemporalAdjusters.firstDayOfMonth()), LocalTime.MIN);
        LocalDateTime toTime = fromTime.with(LocalTime.MAX);//LocalDateTime.of(LocalDate.now(ZoneId.of("+7")).with(TemporalAdjusters.lastDayOfMonth()), LocalTime.MAX);

// TODO unmark this after QA test and mark above 2 lines
//        LocalDate yesterday = now.plusDays(-1);
//        LocalDateTime fromTime = yesterday.atStartOfDay(); //LocalDateTime.of(LocalDate.now(ZoneId.of("+7")).with(TemporalAdjusters.firstDayOfMonth()), LocalTime.MIN);
//        LocalDateTime toTime = fromTime.with(LocalTime.MAX);//LocalDateTime.of(LocalDate.now(ZoneId.of("+7")).with(TemporalAdjusters.lastDayOfMonth()), LocalTime.MAX);

        Serde<BetRecordVo> betRecordVoSerde = new JsonSerde<>(BetRecordVo.class, mapper);
        return (betRecord) -> betRecord
                .filter((key, value) -> {
                    if (value.getSettleTime() == null) {
                        return false;
                    }
                    if (!value.getSettleTime().isAfter(fromTime) || !value.getSettleTime().isBefore(toTime)) {
                        return false;
                    }
                    return true;
                })
                .groupByKey()
                .aggregate(
                        BetRecordVo::new,
                        (key, newBetRecord, aggBetRecord) -> {
                            if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_SETTLED.getValue())) {
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().add(newBetRecord.getValidBetAmount()));
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newBetRecord.getSettle()));
                                aggBetRecord.setWinLoss(aggBetRecord.getWinLoss().add(newBetRecord.getWinLoss()));
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_UPDATE_SETTLE.getValue()) &&
                                    newBetRecord.getSettleDiff() != null) {// 狀態有變注單
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newBetRecord.getSettleDiff()));// 加總差額
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_CANCELED.getValue())) {
                                aggBetRecord.setSettle(aggBetRecord.getSettle().subtract(newBetRecord.getSettle()));// 減去之前的派彩
                                aggBetRecord.setWinLoss(aggBetRecord.getWinLoss().subtract(newBetRecord.getWinLoss()));// 減去之前的輸贏
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().subtract(newBetRecord.getValidBetAmount()));// 減去之前的有效投注
                            }
                            if (aggBetRecord.getValidBetAmount().compareTo(ZERO) != 0 &&
                                    aggBetRecord.getSettle().divide(aggBetRecord.getValidBetAmount(), 5, RoundingMode.HALF_UP).doubleValue() >= getRewardBet()) {
                                log.warn("RewardBet reached!");
                            }
                            return aggBetRecord;
                        },
                        Materialized.<String, BetRecordVo, KeyValueStore<Bytes, byte[]>>as(DAILY_AGG_BET_RECORD)
                                .withKeySerde(Serdes.String()).
                                withValueSerde(betRecordVoSerde)
                );
    }

    /**
     * 加總會員下注金額與中獎金額
     *
     * @return
     */
    @Bean
    public Consumer<KStream<String, BetRecord>> betRecordSettled() {
        Serde<BetRecordVo> betRecordVoSerde = new JsonSerde<>(BetRecordVo.class, mapper);
        return (betRecord) -> betRecord
                .groupByKey()
                .aggregate(
                        BetRecordVo::new,
                        (key, newBetRecord, aggBetRecord) -> {
                            if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_SETTLED.getValue())) {
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().add(newBetRecord.getValidBetAmount()));
                                BigDecimal newSettle = Objects.nonNull(newBetRecord.getSettle()) ? newBetRecord.getSettle() : ZERO;
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newSettle));
                                aggBetRecord.setWinLoss(aggBetRecord.getWinLoss().add(newBetRecord.getWinLoss()));
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_UPDATE_SETTLE.getValue()) &&
                                    newBetRecord.getSettleDiff() != null) {// 狀態有變注單
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newBetRecord.getSettleDiff()));// 加總差額
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_CANCELED.getValue())) {
                                aggBetRecord.setSettle(aggBetRecord.getSettle().subtract(newBetRecord.getSettle()));// 減去之前的派彩
                                aggBetRecord.setWinLoss(aggBetRecord.getWinLoss().subtract(newBetRecord.getWinLoss()));// 減去之前的輸贏
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().subtract(newBetRecord.getValidBetAmount()));// 減去之前的有效投注
                            }
                            if (aggBetRecord.getValidBetAmount().compareTo(ZERO) != 0 &&
                                    aggBetRecord.getSettle().divide(aggBetRecord.getValidBetAmount(), 5, RoundingMode.HALF_UP).doubleValue() >= getRewardBet()) {
                                log.warn("RewardBet reached!");
                            }
                            return aggBetRecord;
                        },
                        Materialized.<String, BetRecordVo, KeyValueStore<Bytes, byte[]>>as(AGG_BET_RECORD)
                                .withKeySerde(Serdes.String()).
                                withValueSerde(betRecordVoSerde)
                );
    }

    /**
     * 加總會員下注金額與中獎金額
     *
     * @return
     */
    @Bean
    public BiConsumer<KStream<String, BetRecord>, KStream<String, WithdrawVO>> betRecordSettledFromLastWithdraw() {
        Serde<BetRecord> betRecordSerde = new JsonSerde<>(BetRecord.class, mapper);
        Serde<BetRecordVo> betRecordVoSerde = new JsonSerde<>(BetRecordVo.class, mapper);
        Serde<WithdrawVO> withdrawSerde = new JsonSerde<>(WithdrawVO.class, mapper);
        return (betRecord, lastWithdraw) -> betRecord
                .outerJoin(lastWithdraw
                                .selectKey((k, v) -> v.getUsername()),
                        (bet, withdraw) -> withdraw == null ? bet : new BetRecord(withdraw.getFinishedTime()),
                        JoinWindows.of(Duration.ofMillis(0))// never join
                        , StreamJoined.with(Serdes.String(), betRecordSerde, withdrawSerde))
                .groupByKey()
                .aggregate(
                        BetRecordVo::new,
                        (key, newBetRecord, aggBetRecord) -> {
                            if (newBetRecord.getLastWithdraw() != null &&// 有提款完成事件
                                    (aggBetRecord.getLastWithdraw() == null || aggBetRecord.getLastWithdraw().isBefore(newBetRecord.getLastWithdraw()))) {
                                aggBetRecord.setValidBetAmount(ZERO);
                                aggBetRecord.setSettle(ZERO);
                                aggBetRecord.setLastWithdraw(newBetRecord.getLastWithdraw());
                                return aggBetRecord;// 更新最後提款時間，並重新加總注單
                            }
                            if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_SETTLED.getValue())) {
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().add(newBetRecord.getValidBetAmount()));
                                BigDecimal newSettle = Objects.nonNull(newBetRecord.getSettle()) ? newBetRecord.getSettle() : ZERO;
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newSettle));
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_UPDATE_SETTLE.getValue()) &&
                                    newBetRecord.getSettleDiff() != null) {// 狀態有變注單
                                aggBetRecord.setSettle(aggBetRecord.getSettle().add(newBetRecord.getSettleDiff()));// 加總差額
                            } else if (Objects.equals(newBetRecord.getEventType(), BetOrderEventTypeEnum.BET_CANCELED.getValue())) {
                                aggBetRecord.setSettle(aggBetRecord.getSettle().subtract(newBetRecord.getSettle()));// 減去之前的派彩
                                aggBetRecord.setValidBetAmount(aggBetRecord.getValidBetAmount().subtract(newBetRecord.getValidBetAmount()));// 減去之前的有效投注
                            }
                            return aggBetRecord;
                        },
                        Materialized.<String, BetRecordVo, KeyValueStore<Bytes, byte[]>>as(AGG_BET_RECORD_FROM_LAST_WITHDRAW)
                                .withKeySerde(Serdes.String()).
                                withValueSerde(betRecordVoSerde)
                );
    }

}
