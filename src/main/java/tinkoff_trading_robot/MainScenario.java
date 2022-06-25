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
    public static String mode = "";

    private static InvestApi api;

    public static void main(String[] args) {

        var token = "";
        log.info("args - " + args[0]);

        try {

            File file = new File("D:/tinkoff_trading_robot/.idea/file.txt");
            FileReader fr = new FileReader(file);
            BufferedReader reader = new BufferedReader(fr);
            String line = reader.readLine();
            while (line != null) {
                if(line.contains("tinkoff_token"))
                    token = line.split("=")[1];
                if(line.contains("telegram_token"))
                    telegram_token = line.split("=")[1];
                if(line.contains("chat_id"))
                    chat_id = line.split("=")[1];
                line = reader.readLine();
            }


            api = InvestApi.create(token);

            Strategy strategy = new Strategy(api);

            if(args[0].equals("all") || args[0].equals("buy")) {
                Runnable task = new Runnable() {
                    public void run() {

                        while (true) {
                            try {
                                strategy.CheckTrends();
                                Thread.sleep(10000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                };
                Thread thread = new Thread(task);
                thread.start();
            }


            if(args[0].equals("all")) {
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
            if(args[0].equals("sell"))
            {
                while (true) {
                    log.info("Run CheckSellStrategy");
                    strategy.CheckSellStrategy();
                    log.info("=================================================================================================================");
                    //log.info("Run CheckBuyStrategy");
                    //strategy.BuyStrategy();
                    //log.info("=================================================================================================================");
                    Thread.sleep(60000);
                }
            }
            if(args[0].equals("buy"))
            {
                while (true) {
                    //log.info("Run CheckSellStrategy");
                    //strategy.CheckSellStrategy();
                    //log.info("=================================================================================================================");
                    log.info("Run CheckBuyStrategy");
                    strategy.BuyStrategy();
                    log.info("=================================================================================================================");
                    Thread.sleep(60000);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

}
