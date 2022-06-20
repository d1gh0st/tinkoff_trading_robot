package tinkoff_trading_robot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Money;
import ru.tinkoff.piapi.core.models.Position;
import tinkoff_trading_robot.classes.Strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ru.tinkoff.piapi.core.utils.DateUtils.timestampToString;
import static ru.tinkoff.piapi.core.utils.MapperUtils.quotationToBigDecimal;

public class MainScenario {

    static final Logger log = LoggerFactory.getLogger(MainScenario.class);

    private static InvestApi api;

    public static void main(String[] args) {

        //String instrumentFigi = "";

        var token = "";
        api = InvestApi.create(token);

        Strategy strategy = new Strategy(api);

        try {
            while (true) {
                log.info("Run CheckSellStrategy1");
                strategy.CheckSellStrategy();
                log.info("=================================================================================================================");
                Thread.sleep(60000);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

}
