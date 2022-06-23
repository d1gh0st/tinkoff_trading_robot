package tinkoff_trading_robot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.core.InvestApi;
import tinkoff_trading_robot.classes.Strategy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class MainScenario {

    static final Logger log = LoggerFactory.getLogger(MainScenario.class);
    public static String telegram_token = "";
    public static String chat_id = "";

    private static InvestApi api;

    public static void main(String[] args) {

        //String instrumentFigi = "";

        //Strategy.sendToTelegram();
        var token = "";

        try {

            File file = new File("D:/tinkoff_trading_robot/.idea/file.txt");
            FileReader fr = new FileReader(file);
            BufferedReader reader = new BufferedReader(fr);
            String line = reader.readLine();
            while (line != null) {
                if(line.contains("tinkoff_token"))
                    token = line;
                if(line.contains("telegram_token"))
                    telegram_token = line;
                if(line.contains("chat_id"))
                    chat_id = line;
                line = reader.readLine();
            }


            api = InvestApi.create(token);

            Strategy strategy = new Strategy(api);



            while (true) {
                log.info("Run CheckSellStrategy");
                strategy.CheckSellStrategy();
                log.info("=================================================================================================================");
                log.info("Run CheckBuyStrategy");
                strategy.BuyStrategy();
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
