package tinkoff_trading_robot.classes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.Instrument;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class Strategy {

    static final Logger log = LoggerFactory.getLogger(Strategy.class);
    private ApiMethods apiMethods;
    private InvestApi api;

    public Strategy(InvestApi investApi)
    {
        api = investApi;
        apiMethods = new ApiMethods(api);
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
                Instrument ins = api.getInstrumentsService().getInstrumentByFigiSync(position.getFigi());
                log.info("Check dividend date for {} ---------------->", ins.getTicker());
                log.debug("Quantity: {}", position.getQuantityLots());
                log.debug("amount of shares in 1 lot: {}",ins.getLot());
                if (!apiMethods.CheckDividendDate(position.getFigi())) {
                    DividendDateDoesNotExist(position,ins);
                }
                else
                {
                    DividendDateExist(position);
                }
            }
        }
    }
}
