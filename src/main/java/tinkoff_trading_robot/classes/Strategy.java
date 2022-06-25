package tinkoff_trading_robot.classes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InstrumentsService;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Position;
import tinkoff_trading_robot.MainScenario;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

public class Strategy {

    static final Logger log = LoggerFactory.getLogger(Strategy.class);
    private ApiMethods apiMethods;
    private InvestApi api;
    private List<Share> shares;
    int counter;
    Map<String,BigDecimal> first_level_buy;
    Map<String,BigDecimal> second_level_buy;
    Map<String,BigDecimal> third_level_buy;
    Map<String,BigDecimal> first_level_sell;
    Map<String,BigDecimal> second_level_sell;
    Map<String,BigDecimal> third_level_sell;
    Map<String,List<BigDecimal>> all_prices;
    List<String> trends;
    int trends_counter;

    public Strategy(InvestApi investApi)
    {
        trends_counter = 0;
        all_prices = new HashMap<>();
        api = investApi;
        apiMethods = new ApiMethods(api);
        shares = downloadShares();
        int local_counter = 0;
        log.info("Loading prices...");
        if(shares.size() > 0 && (MainScenario.mode.equals("all") ||  MainScenario.mode.equals("buy")))
        {

            for(Share share : shares)
            {
                if(local_counter > 200)
                {
                    local_counter = 0;
                    try
                    {
                        Thread.sleep(60000);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                List<BigDecimal> prices = apiMethods.getDaysPrices(share);
                all_prices.put(share.getTicker(),prices);
                local_counter++;
            }
            try
            {
                Thread.sleep(60000);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        counter = 0;
        first_level_buy = new HashMap<>();
        second_level_buy = new HashMap<>();
        third_level_buy = new HashMap<>();
        first_level_sell = new HashMap<>();
        second_level_sell = new HashMap<>();
        third_level_sell = new HashMap<>();
        trends = new ArrayList<>();
    }


    private void DividendDateDoesNotExist(Position position, Instrument ins)
    {
        log.info("Dividend date does not exist in the nearest future ---------------->");
        BigDecimal profit = position.getExpectedYield().divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
        log.info("profit: {}",profit);
        if(profit.doubleValue() > 0) {
            log.info("Profit more then zero ---------------->");
            log.info("Check stop orders");
            if(apiMethods.CheckStopOrder(position.getFigi()))
            {
                StopOrderExist(position, ins);
            }
            else {
                StopOrderDoesNotExist(position,profit,ins);
            }
        }
    }

    private void DividendDateExist(Position position)
    {
        log.info("Dividend date is soon. Check if profit more than dividends ---------------->");
        log.info("Check stop orders");
        if(apiMethods.CheckStopOrder(position.getFigi()))
        {
            StopOrderExist_DividendDate(position);
        }
        else {
            StopOrderDoesNotExist_DividendDate(position);
        }
    }

    private void StopOrderExist(Position position, Instrument ins)
    {
        log.info("Stop order exist ---------------->");
        BigDecimal current_price = apiMethods.getCurrentBuyPrice(position.getFigi());
        if(current_price == null)
        {
            log.info("order-book is unavailable");
        }
        else {
            log.info("Current buy price: {}", current_price);
            BigDecimal buy_price = apiMethods.getStopOrderPrice(position.getFigi());
            log.info("Stop order price: {}", buy_price);
            BigDecimal profit = current_price.subtract(buy_price);
            log.info("Profit from stop order price: {}", profit);
            BigDecimal profit_percent = (current_price.multiply(BigDecimal.valueOf(100)).divide(buy_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent: {}", profit_percent);
            if (profit_percent.doubleValue() > 0.5) {
                log.info("Profit more than 0.5! ---------------->");
                BigDecimal price = buy_price.add(profit.divide(BigDecimal.valueOf(ins.getLot()),2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(ins.getLot()), 2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                if(price.doubleValue() > buy_price.doubleValue()) {
                    log.info("Setup new stop order with price: {}", price);
                    apiMethods.CancelStopOrders(position.getFigi());
                    apiMethods.SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                }
                else
                {
                    log.info("Calculated price is less or equal to stop price");
                }
            }
        }
    }

    private void StopOrderDoesNotExist(Position position, BigDecimal profit, Instrument ins)
    {
        log.info("Stop order does not exist ---------------->");
        BigDecimal current_price = apiMethods.getCurrentBuyPrice(position.getFigi());
        if(current_price == null)
        {
            log.info("order-book is unavailable");
        }
        else {
            log.info("Current buy price: {}", current_price);
            //BigDecimal buy_price = current_price.subtract(profit.divide(BigDecimal.valueOf(ins.getLot()),2, RoundingMode.HALF_UP));
            //BigDecimal buy_price = position.getAveragePositionPrice().getValue();//???rounding??
            BigDecimal buy_price = position.getAveragePositionPriceFifo().getValue();
            log.info("Position price: {}", buy_price);
            BigDecimal profit_percent = (current_price.multiply(BigDecimal.valueOf(100)).divide(buy_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent: {}", profit_percent);
            if (profit_percent.doubleValue() > 0.5) {
                log.info("Profit more than 0.5! ---------------->");
                BigDecimal price = buy_price.add(profit.divide(BigDecimal.valueOf(ins.getLot()), 2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                if(price.doubleValue() > buy_price.doubleValue()) {
                    log.info("Setup new stop order with price: {}", price);
                    apiMethods.SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                }
                else
                {
                    log.info("Calculated price is less or equal to buy price");
                }
            }
        }
    }

    private void StopOrderExist_DividendDate(Position position)
    {
        log.info("Stop order exist");
        BigDecimal stop_price = apiMethods.getStopOrderPrice(position.getFigi());
        BigDecimal buy_price = position.getAveragePositionPrice().getValue();
        BigDecimal cost_now = stop_price.multiply(position.getQuantity());
        BigDecimal cost_before = buy_price.multiply(position.getQuantity());
        BigDecimal profit = cost_now.subtract(cost_before).divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
        BigDecimal dividends = apiMethods.getDividentAmount(position.getFigi());
        if (profit.doubleValue() > dividends.doubleValue()) {
            log.info("Profit more than dividends: {} and {}! ---------------->",profit,dividends);
            BigDecimal profit_percent = (profit.multiply(BigDecimal.valueOf(100)).divide(dividends, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent is {}", profit_percent);
            if (profit_percent.doubleValue() > 0.9) {
                log.info("Profit percent more than 0.9! Setup stop-market order ---------------->");
                BigDecimal price = apiMethods.getCurrentBuyPrice(position.getFigi());
                if (price == null) {
                    log.info("order-book is unavailable");
                } else {
                    price = price.subtract(price.multiply(BigDecimal.valueOf(0.015)));
                    //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                    if(price.doubleValue() > stop_price.doubleValue()) {
                        apiMethods.CancelStopOrders(position.getFigi());
                        log.info("Setup stop order with price {}", price);
                        apiMethods.SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                    }
                    else
                    {
                        log.info("Calculated price is less or equal to stop price");
                    }
                }
            }
        } else {
            log.info("Profit is less then dividends: {} and {}",profit,dividends);
        }
    }

    private void StopOrderDoesNotExist_DividendDate(Position position)
    {
        log.info("Stop order does not exist");
        BigDecimal profit = position.getExpectedYield().divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
        BigDecimal dividends = apiMethods.getDividentAmount(position.getFigi());
        if (profit.doubleValue() > dividends.doubleValue()) {
            log.info("Profit more than dividends: {} and {}! ---------------->",profit,dividends);
            BigDecimal profit_percent = (profit.multiply(BigDecimal.valueOf(100)).divide(dividends, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent is {}", profit_percent);
            if (profit_percent.doubleValue() > 0.9) {
                BigDecimal price = apiMethods.getCurrentBuyPrice(position.getFigi());
                log.info("Profit percent more than 0.9! Setup stop-market order with price {} ---------------->", price);
                if (price == null) {
                    log.info("order-book is unavailable");
                } else {
                    apiMethods.SetupStopMarket(api, position.getFigi(), price,position.getQuantity().longValue());
                }
            }
        } else {
            log.info("Profit is less then dividends: {} and {}",profit,dividends);
        }
    }


    public void CheckSellStrategy()
    {
        List<Position> positions = apiMethods.GetShares();
        for(Position position : positions)
        {
            if(position.getInstrumentType().equals("share")) {
                InstrumentsService instrumentsService = api.getInstrumentsService();
                if(instrumentsService != null) {
                    Instrument ins = instrumentsService.getInstrumentByFigiSync(position.getFigi());
                    log.info("Check dividend date for {} ---------------->", ins.getTicker());
                    log.debug("Quantity: {}", position.getQuantityLots());
                    log.debug("amount of shares in 1 lot: {}", ins.getLot());
                    if (!apiMethods.CheckDividendDate(position.getFigi())) {
                        DividendDateDoesNotExist(position, ins);
                    } else {
                        DividendDateExist(position);
                    }
                }
            }
        }
    }


    public void CheckTrendThirdLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = third_level_buy.get(ticker);
        if(prevBuyPrice != null)
            log.info("Third level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            //if(third_level_buy.get(ticker) == null) {
                log.info("Third level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}", prevBuyPrice, price, ticker);
                sendToTelegram("Third level: Trend confirmed! Buy! Ticker: " + ticker);
                third_level_buy.put(ticker, price);
            //}
            //else second_level_buy.remove(ticker);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() < prevBuyPrice.doubleValue())
        {
            third_level_buy.remove(ticker);
            first_level_buy.remove(ticker);
            second_level_buy.remove(ticker);
            trends.remove(ticker);
        }
        BigDecimal prevSellPrice = third_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("Third level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            //if(third_level_sell.get(ticker) == null) {
                log.info("Third level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}", prevSellPrice, price, ticker);
                sendToTelegram("Third level: Trend confirmed! Sell! Ticker: " + ticker);
                third_level_sell.put(ticker, price);
            //}
            //else second_level_sell.remove(ticker);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else if(price.doubleValue() > prevSellPrice.doubleValue())
        {
            third_level_sell.remove(ticker);
            first_level_sell.remove(ticker);
            second_level_sell.remove(ticker);
            trends.remove(ticker);
        }
    }


    public void CheckTrendSecondLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = second_level_buy.get(ticker);
        if(prevBuyPrice != null)
            log.info("Second level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            if(third_level_buy.get(ticker) == null) {
                log.info("Second level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}", prevBuyPrice, price, ticker);
                //sendToTelegram("Second level: Trend confirmed! Buy! Ticker: " + ticker);
                third_level_buy.put(ticker, price);
            }
            else second_level_buy.remove(ticker);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() <= prevBuyPrice.doubleValue())
        {
            first_level_buy.remove(ticker);
            second_level_buy.remove(ticker);
            trends.remove(ticker);
        }
        BigDecimal prevSellPrice = second_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("Second level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            if(third_level_sell.get(ticker) == null) {
                log.info("Second level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}", prevSellPrice, price, ticker);
                //sendToTelegram("Second level: Trend confirmed! Sell! Ticker: " + ticker);
                third_level_sell.put(ticker, price);
            }
            else second_level_sell.remove(ticker);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else if(price.doubleValue() >= prevSellPrice.doubleValue())
        {
            first_level_sell.remove(ticker);
            second_level_sell.remove(ticker);
            trends.remove(ticker);
        }
    }

    public void CheckTrendFirstLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = first_level_buy.get(ticker);
        if(prevBuyPrice != null)
        log.info("First level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            if(second_level_buy.get(ticker) == null) {
                log.info("First level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}", prevBuyPrice, price, ticker);
                //sendToTelegram("First level: Trend confirmed! Buy! Ticker: " + ticker);
                second_level_buy.put(ticker, price);
            }
            else first_level_buy.remove(ticker);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() <= prevBuyPrice.doubleValue())
        {
            first_level_buy.remove(ticker);
            trends.remove(ticker);
        }
        BigDecimal prevSellPrice = first_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("First level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            if(second_level_sell.get(ticker) == null) {
                log.info("First level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}", prevSellPrice, price, ticker);
                //sendToTelegram("First level: Trend confirmed! Sell! Ticker: " + ticker);
                second_level_sell.put(ticker, price);
            }
            else first_level_sell.remove(ticker);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else  if(price.doubleValue() >= prevSellPrice.doubleValue())
        {
            first_level_sell.remove(ticker);
            trends.remove(ticker);
        }
    }

    public void CheckTrends()
    {
        List<String> work_array = new ArrayList<>(trends);
        for(String ticker : work_array)
        {
            Share trendShare = null;
            for(Share share : shares)
            {
                if (share.getTicker().equals(ticker))
                {
                    trendShare = share;
                    break;
                }
            }
            if(trendShare != null)
            {
                BigDecimal new_price = apiMethods.getLastPrice(trendShare);
                trends_counter++;
                if(new_price.doubleValue() == 0) continue;
                CheckTrendThirdLevel(new_price, ticker);
                CheckTrendSecondLevel(new_price,ticker);
                CheckTrendFirstLevel(new_price,ticker);
            }

        }
    }

    public void BuyStrategy()
    {
        if(counter >= shares.size())
        {
            counter = 0;
            shares = downloadShares();
            if(shares.size() == 0)
            {
                log.info("Market seems to be closed");
                return;
            }
            if(shares.size() != all_prices.size())
            {
                log.info("Update all prices");
                try {
                    Thread.sleep(60000);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }

        //CheckTrends();
        int lastBoundary = counter + 250 - trends_counter;
        if(lastBoundary > shares.size()) lastBoundary = shares.size();
        int sma_count = 0;
        for(int i=counter;i<lastBoundary;i++)
        {
            Share share = shares.get(i);
            //List<BigDecimal> prices = apiMethods.getDaysPrices(share);
            List<BigDecimal> prices = all_prices.get(share.getTicker());
            BigDecimal new_price = apiMethods.getLastPrice(share);
            if(prices != null && prices.size() >= 9 && new_price.doubleValue() != 0) {
                sma_count++;
                SMA(share, prices, new_price);
            }
        }
        log.info("SMA counted: {}",sma_count);
        log.info("SMA buy breakdowns:{}",first_level_buy.size());
        log.info("SMA sell breakdowns:{}",first_level_sell.size());
        log.info("Current range: from {} to {}",counter,lastBoundary);
        counter = lastBoundary;
        log.info("Trend counter: {}", trends_counter);
        trends_counter = 0;
    }


    public List<Share> downloadShares()
    {
        log.info("Download shares");
        List<Share> result = new ArrayList<>();
        try {
            List<Share> shares = apiMethods.GetAllShares();
            for (Share share : shares) {
                if (share.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING)
                    result.add(share);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return result;
    }



    private void SMA(Share share, List<BigDecimal> prices, BigDecimal new_price)
    {
        BigDecimal finalPrice = BigDecimal.valueOf(0);
        log.debug("Calculating SMA");
        String p = "";
        for(int i=prices.size()-9;i<prices.size();i++)
        {
            BigDecimal price = prices.get(i);
            p = p + " " + price.doubleValue();
            finalPrice = finalPrice.add(price);
        }
        log.debug("Prices:{}",p);
        BigDecimal SMA = finalPrice.divide(BigDecimal.valueOf(9),2,RoundingMode.HALF_UP);
        BigDecimal last_price = prices.get(prices.size()-1);
        log.debug("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
        log.debug("new price: {}",new_price);
        if(SMA.doubleValue() > last_price.doubleValue() && new_price.doubleValue() > SMA.doubleValue())
        {
            log.info("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
            log.info("new price: {}",new_price);
            log.info("Ticker {}, SMA Buy!!!", share.getTicker());
            BigDecimal percent = (new_price.multiply(BigDecimal.valueOf(100)).divide(last_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Price growth percent: {}",percent);
            if(percent.doubleValue() > 0.5)
                third_level_buy.put(share.getTicker(),new_price);
            else
                first_level_buy.put(share.getTicker(),new_price);
            trends.add(share.getTicker());

                //sendToTelegram("Ticker: " + share.getTicker() + ", SMA: " + SMA + ", last price: "
                //    + last_price + ", new_price: {} " + new_price + ", percent of growth: " + percent);
        }
        if(SMA.doubleValue() < last_price.doubleValue() && new_price.doubleValue() < SMA.doubleValue())
        {
            log.info("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
            log.info("new price: {}",new_price);
            log.info("Ticker {}, SMA Sell!!!", share.getTicker());
            BigDecimal percent = (new_price.multiply(BigDecimal.valueOf(100)).divide(last_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Price fall percent: {}",percent);
            if(percent.abs().doubleValue() > 0.5)
                third_level_sell.put(share.getTicker(),new_price);
            else
                first_level_sell.put(share.getTicker(),new_price);
            trends.add(share.getTicker());

                //sendToTelegram("Ticker: " + share.getTicker() + ", SMA: " + SMA + ", last price: "
                //    + last_price + ", new_price: {} " + new_price + ", percent of falling: " + percent);
        }

    }


    public static void sendToTelegram(String message) {
        String urlString = "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s";

        //Add Telegram token (given Token is fake)

        String apiToken = MainScenario.telegram_token;

        //Add chatId (given chatId is fake)
        String chatId = MainScenario.chat_id;
        String text = message;

        urlString = String.format(urlString, apiToken, chatId, text);

        try {
            URL url = new URL(urlString);
            URLConnection conn = url.openConnection();
            InputStream is = new BufferedInputStream(conn.getInputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
