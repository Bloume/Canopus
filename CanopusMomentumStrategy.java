package com.motivewave.platform.study.custom;

import com.motivewave.platform.sdk.common.*;
import com.motivewave.platform.sdk.common.desc.*;
import com.motivewave.platform.sdk.study.*;

import java.awt.Color;
import java.util.*;

/**
 * Stratégie Canopus σ - Momentum Mean Reversion HOD/LOD
 * 
 * Principe:
 * - Détecte les mouvements momentum au-delà du 85e percentile
 * - Entrée LONG uniquement
 * - TP1 à +0.8% (sortie 50%)
 * - Trailing stop ATR×1.5 sur les 50% restants
 * - SL à -0.6%
 * 
 * @author Noah - Canopus Trading
 */
@StudyHeader(
    namespace = "com.canopus",
    id = "CANOPUS_MOMENTUM",
    name = "Canopus Momentum Strategy",
    label = "Canopus σ Momentum",
    desc = "Stratégie momentum avec TP1 partiel et trailing stop",
    menu = "Canopus",
    overlay = true,
    studyOverlay = true,
    signals = true,
    strategy = true,
    autoEntry = true,
    manualEntry = false
)
public class CanopusMomentumStrategy extends Study
{
    // Paramètres
    enum Values { MOMENTUM_SIGNAL, ATR }
    
    // Variables d'état
    private boolean tp1Hit = false;
    private double entryPrice = 0;
    private double tp1Price = 0;
    private double highestPrice = 0;
    private double trailingStop = 0;
    private int tradeCount = 0;
    
    @Override
    public void initialize(Defaults defaults)
    {
        SettingsDescriptor sd = new SettingsDescriptor();
        setSettingsDescriptor(sd);
        
        // Groupe: Signaux
        SettingTab signalsTab = new SettingTab("Signaux");
        sd.addTab(signalsTab);
        
        SettingGroup signalsGroup = new SettingGroup("Paramètres Momentum");
        signalsGroup.addRow(new IntegerDescriptor("MOMENTUM_PERCENTILE", "Momentum Percentile", 85, 50, 99, 1,
            "Seuil de momentum (85 = top 15%)"));
        signalsGroup.addRow(new IntegerDescriptor("LOOKBACK_WINDOW", "Lookback Window", 20, 10, 50, 1,
            "Nombre de barres pour calcul momentum"));
        signalsTab.addGroup(signalsGroup);
        
        // Groupe: Gestion de position
        SettingTab managementTab = new SettingTab("Gestion");
        sd.addTab(managementTab);
        
        SettingGroup managementGroup = new SettingGroup("Stop & Targets");
        managementGroup.addRow(new DoubleDescriptor("STOP_LOSS_PCT", "Stop Loss %", 0.6, 0.1, 5.0, 0.1,
            "Stop loss en % du prix d'entrée"));
        managementGroup.addRow(new DoubleDescriptor("TP1_PCT", "Take Profit 1 %", 0.8, 0.1, 5.0, 0.1,
            "Premier TP en % (sortie partielle 50%)"));
        managementGroup.addRow(new DoubleDescriptor("TRAILING_ATR_MULT", "Trailing ATR Multiplier", 1.5, 0.5, 5.0, 0.1,
            "Multiplicateur ATR pour trailing stop"));
        managementGroup.addRow(new IntegerDescriptor("ATR_PERIOD", "ATR Period", 14, 5, 50, 1,
            "Période pour calcul ATR"));
        managementTab.addGroup(managementGroup);
        
        // Groupe: Position sizing
        SettingGroup positionGroup = new SettingGroup("Position");
        positionGroup.addRow(new IntegerDescriptor("CONTRACTS", "Nombre de Contrats", 1, 1, 10, 1,
            "Nombre de contrats MNQ par trade"));
        managementTab.addGroup(positionGroup);
        
        // Groupe: Session de trading
        SettingTab sessionTab = new SettingTab("Session");
        sd.addTab(sessionTab);
        
        SettingGroup sessionGroup = new SettingGroup("Heures de Trading (NY)");
        sessionGroup.addRow(new IntegerDescriptor("SESSION_START_HOUR", "Heure Début", 3, 0, 23, 1,
            "Heure de début (NY) - 3 = 09:00 Paris"));
        sessionGroup.addRow(new IntegerDescriptor("SESSION_START_MIN", "Minute Début", 0, 0, 59, 1,
            "Minute de début"));
        sessionGroup.addRow(new IntegerDescriptor("SESSION_END_HOUR", "Heure Fin", 16, 0, 23, 1,
            "Heure de fin (NY) - 16 = 22:00 Paris"));
        sessionGroup.addRow(new IntegerDescriptor("SESSION_END_MIN", "Minute Fin", 0, 0, 59, 1,
            "Minute de fin"));
        sessionTab.addGroup(sessionGroup);
        
        // Runtime descriptor
        RuntimeDescriptor rd = new RuntimeDescriptor();
        setRuntimeDescriptor(rd);
        
        rd.declarePath(Values.MOMENTUM_SIGNAL, "Signal Momentum");
        rd.declareIndicator(Values.ATR, "ATR");
        rd.setLabelSettings("MOMENTUM_PERCENTILE", "LOOKBACK_WINDOW");
        rd.setLabelPrefix("Canopus");
        
        // Couleurs
        rd.setPathColor(Values.MOMENTUM_SIGNAL, Color.GREEN);
        rd.setPathColor(Values.ATR, Color.ORANGE);
    }
    
    @Override
    protected void calculate(int index, DataContext ctx)
    {
        // Attendre assez de barres
        int lookback = getSettings().getInteger("LOOKBACK_WINDOW", 20);
        if (index < lookback) {
            return;
        }
        
        // Vérifier session de trading
        if (!isInTradingSession(ctx, index)) {
            return;
        }
        
        DataSeries series = ctx.getDataSeries();
        
        // Calculer momentum
        double currentReturn = (series.getClose(index) - series.getClose(index - 1)) / series.getClose(index - 1);
        
        // Construire liste des rendements récents
        List<Double> recentReturns = new ArrayList<>();
        for (int i = 1; i <= lookback; i++) {
            double ret = (series.getClose(index - i + 1) - series.getClose(index - i)) / series.getClose(index - i);
            recentReturns.add(ret);
        }
        
        // Trier pour calculer percentile
        Collections.sort(recentReturns);
        int momentumPercentile = getSettings().getInteger("MOMENTUM_PERCENTILE", 85);
        int thresholdIndex = (int)(recentReturns.size() * momentumPercentile / 100.0);
        double momentumThreshold = recentReturns.get(Math.min(thresholdIndex, recentReturns.size() - 1));
        
        // Calculer ATR
        double atr = calculateATR(ctx, index);
        series.setDouble(index, Values.ATR, atr);
        
        // Vérifier position ouverte
        if (ctx.isLong()) {
            manageLongPosition(ctx, index, atr);
            return;
        }
        
        // Signal d'entrée
        if (currentReturn > momentumThreshold && !ctx.isLong()) {
            series.setBoolean(index, Values.MOMENTUM_SIGNAL, true);
            enterLongPosition(ctx, index);
        } else {
            series.setBoolean(index, Values.MOMENTUM_SIGNAL, false);
        }
    }
    
    private void enterLongPosition(DataContext ctx, int index)
    {
        DataSeries series = ctx.getDataSeries();
        double price = series.getClose(index);
        
        int contracts = getSettings().getInteger("CONTRACTS", 1);
        double slPct = getSettings().getDouble("STOP_LOSS_PCT", 0.6) / 100.0;
        double tp1Pct = getSettings().getDouble("TP1_PCT", 0.8) / 100.0;
        
        // Sauvegarder état
        entryPrice = price;
        tp1Price = price * (1 + tp1Pct);
        highestPrice = price;
        tp1Hit = false;
        trailingStop = 0;
        
        // Ordre d'entrée
        MarketOrder entry = new MarketOrder(OrderAction.BUY, contracts);
        ctx.buy(entry);
        
        // Stop Loss
        double slPrice = price * (1 - slPct);
        StopOrder sl = new StopOrder(OrderAction.SELL, contracts, slPrice);
        ctx.sell(sl);
        
        // Take Profit 1 (50%)
        int tp1Contracts = contracts / 2;
        if (tp1Contracts > 0) {
            LimitOrder tp1 = new LimitOrder(OrderAction.SELL, tp1Contracts, tp1Price);
            ctx.sell(tp1);
        }
        
        tradeCount++;
        
        info("ENTRÉE LONG #" + tradeCount + " | Prix: " + price + 
             " | TP1: " + tp1Price + " | SL: " + slPrice);
    }
    
    private void manageLongPosition(DataContext ctx, int index, double atr)
    {
        DataSeries series = ctx.getDataSeries();
        double price = series.getClose(index);
        
        // Vérifier si TP1 atteint
        if (!tp1Hit && price >= tp1Price) {
            tp1Hit = true;
            highestPrice = price;
            
            double trailingMult = getSettings().getDouble("TRAILING_ATR_MULT", 1.5);
            trailingStop = price - (atr * trailingMult);
            
            info("TP1 HIT à " + price + " | Activation trailing à " + trailingStop);
        }
        
        // Gérer trailing stop
        if (tp1Hit) {
            if (price > highestPrice) {
                highestPrice = price;
                double trailingMult = getSettings().getDouble("TRAILING_ATR_MULT", 1.5);
                trailingStop = price - (atr * trailingMult);
                
                debug("Nouveau high: " + highestPrice + " | Trailing: " + trailingStop);
            }
            
            // Exit sur trailing stop
            if (price <= trailingStop) {
                int remainingContracts = ctx.getPosition();
                if (remainingContracts > 0) {
                    MarketOrder exit = new MarketOrder(OrderAction.SELL, remainingContracts);
                    ctx.sell(exit);
                    
                    info("SORTIE Trailing à " + price + " | " + remainingContracts + " contrats");
                }
            }
        }
    }
    
    private double calculateATR(DataContext ctx, int index)
    {
        DataSeries series = ctx.getDataSeries();
        int period = getSettings().getInteger("ATR_PERIOD", 14);
        
        double sum = 0;
        for (int i = 0; i < period; i++) {
            double high = series.getHigh(index - i);
            double low = series.getLow(index - i);
            double tr = high - low;
            sum += tr;
        }
        
        return sum / period;
    }
    
    private boolean isInTradingSession(DataContext ctx, int index)
    {
        DataSeries series = ctx.getDataSeries();
        long time = series.getStartTime(index);
        
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        
        int startHour = getSettings().getInteger("SESSION_START_HOUR", 3);
        int startMin = getSettings().getInteger("SESSION_START_MIN", 0);
        int endHour = getSettings().getInteger("SESSION_END_HOUR", 16);
        int endMin = getSettings().getInteger("SESSION_END_MIN", 0);
        
        int currentTime = hour * 60 + minute;
        int sessionStart = startHour * 60 + startMin;
        int sessionEnd = endHour * 60 + endMin;
        
        return currentTime >= sessionStart && currentTime <= sessionEnd;
    }
}
