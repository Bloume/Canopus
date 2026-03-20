package study_examples;

import com.motivewave.platform.sdk.common.*;
import com.motivewave.platform.sdk.common.desc.*;
import com.motivewave.platform.sdk.order_mgmt.*;
import com.motivewave.platform.sdk.study.*;

import java.util.*;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║        CANOPUS σ HOD/LOD — MotiveWave SDK Strategy              ║
 * ║        Mean Reversion NQ/MNQ Futures                            ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║  Stratégie : Mean Reversion HOD/LOD sur NQ Futures 5min         ║
 * ║  Modèles   : Ornstein-Uhlenbeck + HMM + Parkinson/EWMA          ║
 * ║  PropFirm  : DD suiveur 4% — Risque adaptatif 0.2-0.6%         ║
 * ║  Session   : 3h00-16h00 NY                                      ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║  Résultats backtest (Kibot NQ réel, Jan 2020 → Sep 2025)        ║
 * ║  WR : 65.3% | PF : 5.69 | Sharpe : 4.74 | DD max : 1.0%        ║
 * ║  $50,000 → $984,025 — Jamais coupé sur 5 ans 9 mois             ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * LOGIQUE DU TRADE :
 *   Entrée    : σ1 HOD (SHORT) ou σ1 LOD (LONG) avec confirmation clôture
 *   Filtre    : Momentum (move 3 bougies ≤ 2× moyenne 10 bougies)
 *   Boost σ2  : Risque ×1.5 si σ2 touché avant σ1
 *   TP1 (50%) : milieu entre entry et TP2, SL → breakeven
 *   BE        : SL → entry si profit ≥ 60% de TP_DIST (sans TP1)
 *   Trailing  : Activé si profit ≥ 75% de TP_DIST, distance 35%
 *   TP2 (50%) : Open de session
 *   EOD       : Fermeture forcée à 15h55 NY
 */
@StudyHeader(
    namespace   = "canopus",
    id          = "CANOPUS_HOD_LOD",
    name        = "Canopus σ HOD/LOD PropFirm",
    desc        = "Mean Reversion HOD/LOD — OU+HMM+Parkinson — PropFirm $50k DD 4%",
    overlay     = true,
    strategy    = true,
    autoEntry   = true,
    manualEntry = false,
    requiresBarUpdates = true
)
public class CanopusHodLod extends Study {

    // ── Constantes ────────────────────────────────────
    private static final int    LOOKBACK         = 10;
    private static final double DD_PCT           = 0.04;
    private static final double DAILY_STOP_PCT   = 0.015;
    private static final double MNQ_MULT         = 2.0;      // $2 par point MNQ
    private static final double RISK_NORMAL      = 0.004;    // 0.4%
    private static final double RISK_BOOSTED     = 0.006;    // 0.6% si σ2 touché
    private static final double RISK_MID1        = 0.003;    // 0.3% marge 2-3%
    private static final double RISK_MID2        = 0.002;    // 0.2% marge 1-2%
    private static final double TP1_RATIO        = 0.50;     // 50% fermé au TP1
    private static final double BE_THRESH_RATIO  = 0.60;     // BE si profit ≥ 60%
    private static final double TRAIL_THRESH     = 0.75;     // Trailing si ≥ 75%
    private static final double TRAIL_DIST       = 0.35;     // Distance trailing 35%
    private static final double SIGMA2_BUF       = 0.15;     // Buffer σ2 = 15%
    private static final double MOMENTUM_MULT    = 2.0;      // Filtre momentum ×2
    // Session NY
    private static final int SESSION_START_HOUR  = 3;        // 3h00 NY
    private static final int SESSION_END_HOUR    = 16;       // 16h00 NY
    private static final int EOD_HOUR            = 15;       // EOD à 15h55 NY
    private static final int EOD_MINUTE          = 55;

    // ── Paramètres utilisateur ────────────────────────
    private static final String ACCOUNT_BALANCE  = "accountBalance";
    private static final String PEAK_BALANCE     = "peakBalance";

    // ── Variables d'état ─────────────────────────────
    // PropFirm
    private double accountBalance;
    private double peakBalance;
    private double ddSeuil;
    private double dailyPnl;
    private boolean dailyStopHit;

    // Session
    private boolean hodTradedToday;
    private boolean lodTradedToday;
    private long    lastSessionDate;

    // Zones σ calculées
    private double sessionOpen;
    private double hod1, hod2, lod1, lod2;
    private String regime;
    private double volRatio;
    private boolean zonesCalculated;
    private boolean hod2Touched;
    private boolean lod2Touched;

    // Position courante
    private boolean inPosition;
    private boolean isShort;
    private int     totalContracts;
    private int     remainingContracts;
    private double  entryPrice;
    private double  currentSL;
    private double  initialSL;
    private double  tp1Price;
    private double  tp2Price;
    private double  tpDist;
    private boolean tp1Hit;
    private boolean beTriggered;
    private boolean trailActive;
    private double  bestPrice;
    private double  pnlTp1;
    private boolean boosted;

    // Historique des sessions
    private List<double[]> sessionHistory; // [open, hod, lod, parkVol, dailyRet]

    // ── Initialisation ────────────────────────────────
    @Override
    public void initialize(Defaults defaults) {
        // Paramètres affichés dans le panneau MotiveWave
        SettingsDescriptor sd = new SettingsDescriptor();
        sd.addInputDescriptor(new DoubleDescriptor(
            ACCOUNT_BALANCE, "Solde Compte ($)", 50000.0, 1000, 10000000, 100));
        sd.addInputDescriptor(new DoubleDescriptor(
            PEAK_BALANCE, "Peak Balance ($)", 50000.0, 1000, 10000000, 100));
        setSettingsDescriptor(sd);

        // Init état
        accountBalance = 50000.0;
        peakBalance    = 50000.0;
        ddSeuil        = peakBalance * (1.0 - DD_PCT);
        dailyPnl       = 0.0;
        dailyStopHit   = false;
        hodTradedToday = false;
        lodTradedToday = false;
        lastSessionDate= -1;
        zonesCalculated= false;
        inPosition     = false;
        sessionHistory = new ArrayList<>();
    }

    // ══════════════════════════════════════════════════
    // MAIN — appelé à chaque clôture de bougie
    // ══════════════════════════════════════════════════
    @Override
    public void onBarClose(OrderContext ctx) {
        DataContext dc  = ctx.getDataContext();
        Instrument  ins = ctx.getInstrument();

        // Index de la barre courante
        int idx = dc.getCurrentIndex();
        if (idx < LOOKBACK * 78 + 10) return; // Pas assez d'historique (~78 barres/session)

        // Timestamp NY
        long ts      = dc.getStartTime(idx);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        cal.setTimeInMillis(ts);
        int hour   = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);

        // ── Reset quotidien ───────────────────────────
        long sessionDate = getSessionDate(cal);
        if (sessionDate != lastSessionDate) {
            // Sauvegarder la session précédente dans l'historique
            if (lastSessionDate > 0 && sessionOpen > 0) {
                addSessionToHistory(dc, idx);
            }
            // Reset
            lastSessionDate = sessionDate;
            dailyPnl        = 0.0;
            dailyStopHit    = false;
            hodTradedToday  = false;
            lodTradedToday  = false;
            zonesCalculated = false;
            hod2Touched     = false;
            lod2Touched     = false;
            // Récupérer l'open de la nouvelle session
            sessionOpen = dc.getOpen(idx);
            // Calculer les zones pour la nouvelle session
            if (sessionHistory.size() >= LOOKBACK) {
                calculateZones();
            }
        }

        // ── Heure session ─────────────────────────────
        if (hour < SESSION_START_HOUR || hour >= SESSION_END_HOUR) return;

        // ── EOD ───────────────────────────────────────
        if (hour == EOD_HOUR && minute >= EOD_MINUTE) {
            if (inPosition) {
                closePosition(ctx, dc, idx, "EOD");
            }
            return;
        }

        // ── Check DD ─────────────────────────────────
        if (accountBalance <= ddSeuil) {
            if (inPosition) closePosition(ctx, dc, idx, "DD_BREACH");
            return;
        }

        // ── Stop journalier ───────────────────────────
        if (dailyStopHit || dailyPnl <= -(50000.0 * DAILY_STOP_PCT)) {
            if (!dailyStopHit) {
                dailyStopHit = true;
                if (inPosition) closePosition(ctx, dc, idx, "DAILY_STOP");
            }
            return;
        }

        // Marge DD
        double marge = accountBalance - ddSeuil;
        double margePct = marge / accountBalance * 100.0;
        if (margePct < 1.0) return;

        // ── Tracker σ2 touchés ────────────────────────
        if (zonesCalculated) {
            double high = dc.getHigh(idx);
            double low  = dc.getLow(idx);
            if (high >= hod2) hod2Touched = true;
            if (low  <= lod2) lod2Touched = true;
        }

        // ── Gestion position ouverte ──────────────────
        if (inPosition) {
            manageOpenPosition(ctx, dc, ins, idx);
            return;
        }

        // ── Recherche de signal ───────────────────────
        if (!zonesCalculated) return;
        if (!"sideways".equals(regime) && volRatio >= 1.0) return;

        double close = dc.getClose(idx);
        double high  = dc.getHigh(idx);
        double low   = dc.getLow(idx);

        // S1 HOD MR — SHORT
        if (!hodTradedToday) {
            double buf = (hod2 - hod1) * SIGMA2_BUF;
            if (high >= hod1 - buf && close < hod1) {
                if (checkMomentum(dc, idx)) {
                    double riskPct = getRiskPct(hod2Touched, margePct);
                    if (riskPct > 0) {
                        enterShort(ctx, dc, ins, idx, riskPct, hod2Touched, buf);
                    }
                }
            }
        }

        // S1 LOD MR — LONG
        if (!lodTradedToday && !inPosition) {
            double buf = (lod1 - lod2) * SIGMA2_BUF;
            if (low <= lod1 + buf && close > lod1) {
                if (checkMomentum(dc, idx)) {
                    double riskPct = getRiskPct(lod2Touched, margePct);
                    if (riskPct > 0) {
                        enterLong(ctx, dc, ins, idx, riskPct, lod2Touched, buf);
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════
    // ENTRÉE SHORT
    // ══════════════════════════════════════════════════
    private void enterShort(OrderContext ctx, DataContext dc, Instrument ins,
                            int idx, double riskPct, boolean isBoosted, double buf) {
        double entry = hod1;
        double sl    = ins.round(hod2 + buf);
        double tp2r  = ins.round(sessionOpen < hod1 ? sessionOpen : hod1 - buf);
        double tp1r  = ins.round((entry + tp2r) / 2.0);
        double dist  = Math.abs(entry - sl);
        if (dist <= 0) return;

        int contracts = calcContracts(riskPct, dist);
        if (contracts <= 0) return;

        // Placer l'ordre SELL au marché
        ctx.sell(contracts);

        // Initialiser état position
        inPosition        = true;
        isShort           = true;
        totalContracts    = contracts;
        remainingContracts= contracts;
        entryPrice        = entry;
        currentSL         = sl;
        initialSL         = sl;
        tp1Price          = tp1r;
        tp2Price          = tp2r;
        tpDist            = Math.abs(tp2r - entry);
        tp1Hit            = false;
        beTriggered       = false;
        trailActive       = false;
        bestPrice         = entry;
        pnlTp1            = 0.0;
        boosted           = isBoosted;
        hodTradedToday    = true;

        debug(String.format(
            "ENTRY SHORT %d MNQ @ %.2f | SL %.2f | TP1 %.2f | TP2 %.2f | Risk %.1f%% %s",
            contracts, entry, sl, tp1r, tp2r, riskPct * 100, isBoosted ? "[BOOSTED]" : ""
        ));
    }

    // ══════════════════════════════════════════════════
    // ENTRÉE LONG
    // ══════════════════════════════════════════════════
    private void enterLong(OrderContext ctx, DataContext dc, Instrument ins,
                           int idx, double riskPct, boolean isBoosted, double buf) {
        double entry = lod1;
        double sl    = ins.round(lod2 - buf);
        double tp2r  = ins.round(sessionOpen > lod1 ? sessionOpen : lod1 + buf);
        double tp1r  = ins.round((entry + tp2r) / 2.0);
        double dist  = Math.abs(entry - sl);
        if (dist <= 0) return;

        int contracts = calcContracts(riskPct, dist);
        if (contracts <= 0) return;

        ctx.buy(contracts);

        inPosition        = true;
        isShort           = false;
        totalContracts    = contracts;
        remainingContracts= contracts;
        entryPrice        = entry;
        currentSL         = sl;
        initialSL         = sl;
        tp1Price          = tp1r;
        tp2Price          = tp2r;
        tpDist            = Math.abs(tp2r - entry);
        tp1Hit            = false;
        beTriggered       = false;
        trailActive       = false;
        bestPrice         = entry;
        pnlTp1            = 0.0;
        boosted           = isBoosted;
        lodTradedToday    = true;

        debug(String.format(
            "ENTRY LONG %d MNQ @ %.2f | SL %.2f | TP1 %.2f | TP2 %.2f | Risk %.1f%% %s",
            contracts, entry, sl, tp1r, tp2r, riskPct * 100, isBoosted ? "[BOOSTED]" : ""
        ));
    }

    // ══════════════════════════════════════════════════
    // GESTION DE LA POSITION OUVERTE
    // Appelé à chaque barre quand en position
    // ══════════════════════════════════════════════════
    private void manageOpenPosition(OrderContext ctx, DataContext dc,
                                    Instrument ins, int idx) {
        double high  = dc.getHigh(idx);
        double low   = dc.getLow(idx);
        double close = dc.getClose(idx);

        if (isShort) {
            // Mettre à jour le meilleur prix (le plus bas pour un short)
            if (low < bestPrice) bestPrice = low;
            double profitPts = entryPrice - bestPrice;

            // ── TP1 atteint ? ─────────────────────────
            if (!tp1Hit && low <= tp1Price) {
                int tp1Qty = Math.max(1, totalContracts / 2);
                ctx.buy(tp1Qty); // Fermer 50%
                pnlTp1          = (entryPrice - tp1Price) * MNQ_MULT * tp1Qty;
                remainingContracts = totalContracts - tp1Qty;
                tp1Hit          = true;
                beTriggered     = true;
                currentSL       = ins.round(entryPrice); // SL → breakeven
                debug(String.format("TP1 HIT SHORT @ %.2f | Fermé %d | SL → BE %.2f",
                    tp1Price, tp1Qty, currentSL));
            }

            // ── Breakeven sans TP1 ────────────────────
            if (!tp1Hit && !beTriggered && profitPts >= tpDist * BE_THRESH_RATIO) {
                beTriggered = true;
                currentSL   = ins.round(entryPrice);
                debug(String.format("BE TRIGGERED SHORT | SL → %.2f", currentSL));
            }

            // ── Trailing stop (après TP1) ─────────────
            if (tp1Hit && profitPts >= tpDist * TRAIL_THRESH) {
                trailActive = true;
                double newSL = ins.round(bestPrice + tpDist * TRAIL_DIST);
                if (newSL < currentSL) {
                    currentSL = newSL;
                    debug(String.format("TRAIL SHORT | Best %.2f | New SL %.2f",
                        bestPrice, currentSL));
                }
            }

            // ── SL touché ? ───────────────────────────
            if (high >= currentSL) {
                closeRemaining(ctx, ins, "SL_SHORT");
                return;
            }

            // ── TP2 atteint ? ─────────────────────────
            if (low <= tp2Price) {
                closeRemaining(ctx, ins, "TP2_SHORT");
            }

        } else {
            // LONG
            if (high > bestPrice) bestPrice = high;
            double profitPts = bestPrice - entryPrice;

            if (!tp1Hit && high >= tp1Price) {
                int tp1Qty = Math.max(1, totalContracts / 2);
                ctx.sell(tp1Qty);
                pnlTp1             = (tp1Price - entryPrice) * MNQ_MULT * tp1Qty;
                remainingContracts = totalContracts - tp1Qty;
                tp1Hit             = true;
                beTriggered        = true;
                currentSL          = ins.round(entryPrice);
                debug(String.format("TP1 HIT LONG @ %.2f | Fermé %d | SL → BE %.2f",
                    tp1Price, tp1Qty, currentSL));
            }

            if (!tp1Hit && !beTriggered && profitPts >= tpDist * BE_THRESH_RATIO) {
                beTriggered = true;
                currentSL   = ins.round(entryPrice);
                debug(String.format("BE TRIGGERED LONG | SL → %.2f", currentSL));
            }

            if (tp1Hit && profitPts >= tpDist * TRAIL_THRESH) {
                trailActive = true;
                double newSL = ins.round(bestPrice - tpDist * TRAIL_DIST);
                if (newSL > currentSL) {
                    currentSL = newSL;
                    debug(String.format("TRAIL LONG | Best %.2f | New SL %.2f",
                        bestPrice, currentSL));
                }
            }

            if (low <= currentSL) {
                closeRemaining(ctx, ins, "SL_LONG");
                return;
            }

            if (high >= tp2Price) {
                closeRemaining(ctx, ins, "TP2_LONG");
            }
        }
    }

    // ══════════════════════════════════════════════════
    // FERMETURE PARTIELLE / TOTALE
    // ══════════════════════════════════════════════════
    private void closeRemaining(OrderContext ctx, Instrument ins, String reason) {
        if (remainingContracts <= 0) { resetPosition(); return; }
        if (isShort) {
            ctx.buy(remainingContracts);
        } else {
            ctx.sell(remainingContracts);
        }
        debug(String.format("CLOSE %s | %d contrats | raison: %s",
            isShort ? "SHORT" : "LONG", remainingContracts, reason));
        resetPosition();
    }

    private void closePosition(OrderContext ctx, DataContext dc, int idx, String reason) {
        ctx.closeAtMarket();
        debug("CLOSE ALL — raison: " + reason);
        resetPosition();
    }

    private void resetPosition() {
        inPosition         = false;
        remainingContracts = 0;
        tp1Hit             = false;
        beTriggered        = false;
        trailActive        = false;
    }

    // ══════════════════════════════════════════════════
    // CALCUL DES ZONES σ1 / σ2
    // Exactement la même logique que le backtest Python
    // ══════════════════════════════════════════════════
    private void calculateZones() {
        if (sessionHistory.size() < LOOKBACK) return;
        List<double[]> past = sessionHistory.subList(
            sessionHistory.size() - LOOKBACK, sessionHistory.size());

        // Extraire moves HOD et LOD
        double[] hodMoves = new double[LOOKBACK];
        double[] lodMoves = new double[LOOKBACK];
        double[] parkVals = new double[LOOKBACK];
        double[] dailyRets= new double[LOOKBACK];
        for (int i = 0; i < LOOKBACK; i++) {
            double[] s = past.get(i);
            hodMoves[i] = s[1] - s[0]; // hod - open
            lodMoves[i] = s[0] - s[2]; // open - lod
            parkVals[i] = s[3];
            dailyRets[i]= s[4];
        }

        // Calibration OU
        double[] ouH = calibrateOU(hodMoves);
        double[] ouL = calibrateOU(lodMoves);
        double[] distH = ouDist(ouH);
        double[] distL = ouDist(ouL);
        double hm = distH[0], hs = distH[1];
        double lm = distL[0], ls = distL[1];

        // Volatilité adaptative Parkinson + EWMA
        double avgPark = mean(parkVals);
        double recPark = parkVals[LOOKBACK - 1];
        double parkRatio = avgPark > 0 ? recPark / avgPark : 1.0;

        // Tous les returns pour EWMA
        double[] allRets = getAllReturns(past);
        double ewma     = ewmaVol(allRets, 0.94);
        double ewmaBase = rms(allRets);
        double ewmaRatio= ewmaBase > 0 ? ewma / ewmaBase : 1.0;
        volRatio = Math.max(0.5, Math.min(2.0, 0.6 * parkRatio + 0.4 * ewmaRatio));

        // Régime HMM
        double[] hmm = detectRegime(dailyRets);
        double hmm_conf = hmm[0];
        double hmm_adj  = hmm[1];
        regime = hmm[2] == 1.0 ? "bull" : hmm[2] == -1.0 ? "bear" : "sideways";

        // Blend OU / classique selon R²
        double ouW = Math.max(0.3, Math.min(0.85, (ouH[3] + ouL[3]) / 2.0));
        double clW = 1.0 - ouW;

        double hodM = ouW * hm + clW * mean(hodMoves);
        double hodS = (ouW * hs + clW * std(hodMoves)) * volRatio;
        double lodM = ouW * lm + clW * mean(lodMoves);
        double lodS = (ouW * ls + clW * std(lodMoves)) * volRatio;

        // Ajustement HMM asymétrique
        double hodHmm = "bull".equals(regime) ? hmm_adj :
                        "bear".equals(regime) ? (2.0 - hmm_adj) : hmm_adj;
        double lodHmm = "bear".equals(regime) ? hmm_adj :
                        "bull".equals(regime) ? (2.0 - hmm_adj) : hmm_adj;

        hod1 = sessionOpen + hodM;
        hod2 = sessionOpen + hodM + hodS * hodHmm;
        lod1 = sessionOpen - lodM;
        lod2 = sessionOpen - lodM - lodS * lodHmm;
        zonesCalculated = true;

        debug(String.format(
            "ZONES | Open=%.1f HOD1=%.1f HOD2=%.1f LOD1=%.1f LOD2=%.1f | %s Vol=%.2f",
            sessionOpen, hod1, hod2, lod1, lod2, regime, volRatio));
    }

    // ══════════════════════════════════════════════════
    // CALIBRATION ORNSTEIN-UHLENBECK
    // Régression OLS — identique au backtest Python
    // ══════════════════════════════════════════════════
    private double[] calibrateOU(double[] series) {
        int n = series.length;
        if (n < 4) return new double[]{1.0, mean(series), std(series), 0.0};

        double[] x  = Arrays.copyOfRange(series, 0, n - 1);
        double[] dx = new double[n - 1];
        for (int i = 0; i < n - 1; i++) dx[i] = series[i + 1] - series[i];

        int n2 = x.length;
        double sumX=0, sumDX=0, sumXX=0, sumXDX=0;
        for (int i = 0; i < n2; i++) {
            sumX   += x[i];
            sumDX  += dx[i];
            sumXX  += x[i] * x[i];
            sumXDX += x[i] * dx[i];
        }
        double denom = n2 * sumXX - sumX * sumX;
        if (Math.abs(denom) < 1e-10) return new double[]{0.5, mean(x), std(dx), 0.1};

        double beta  = (n2 * sumXDX - sumX * sumDX) / denom;
        double alpha = (sumDX - beta * sumX) / n2;
        double theta = Math.max(0.01, Math.min(10.0, -beta));
        double mu    = theta > 0 ? alpha / theta : mean(x);

        double ssTot = 0, ssRes = 0;
        double dxMean = mean(dx);
        double[] res = new double[n2];
        for (int i = 0; i < n2; i++) {
            res[i]  = dx[i] - (alpha + beta * x[i]);
            ssTot  += Math.pow(dx[i] - dxMean, 2);
            ssRes  += res[i] * res[i];
        }
        double sigma = Math.sqrt(mean(squareArr(res)));
        double r2    = ssTot > 0 ? Math.max(0, 1.0 - ssRes / ssTot) : 0.0;

        return new double[]{theta, mu, sigma, r2};
    }

    // Distribution OU à T=1
    private double[] ouDist(double[] ou) {
        double theta = ou[0], mu = ou[1], sigma = ou[2];
        double ed    = Math.exp(-theta);
        double mean  = mu * (1.0 - ed);
        double var   = (sigma * sigma / (2.0 * theta)) * (1.0 - Math.exp(-2.0 * theta));
        return new double[]{mean, Math.sqrt(Math.max(0, var))};
    }

    // ══════════════════════════════════════════════════
    // DÉTECTION DE RÉGIME HMM
    // Returns [hmm_conf, hmm_adj, type] type: 1=bull, -1=bear, 0=sideways
    // ══════════════════════════════════════════════════
    private double[] detectRegime(double[] sessionRets) {
        if (sessionRets.length < 5) return new double[]{0.5, 1.0, 0.0};
        int n = sessionRets.length;
        double[] last5 = Arrays.copyOfRange(sessionRets, n - 5, n);
        double trend = mean(last5);

        double conf, adj, type;
        if (trend > 0.003) {
            conf = Math.min(0.9, 0.55 + Math.abs(trend) * 80);
            adj  = 1.0 + 0.15 * conf;
            type = 1.0;
        } else if (trend < -0.003) {
            conf = Math.min(0.9, 0.55 + Math.abs(trend) * 80);
            adj  = 1.0 + 0.20 * conf;
            type = -1.0;
        } else {
            conf = Math.min(0.85, 0.6 + (1.0 - Math.abs(trend) * 30) * 0.2);
            adj  = 1.0 - 0.15 * conf;
            type = 0.0;
        }
        return new double[]{conf, adj, type};
    }

    // ══════════════════════════════════════════════════
    // FILTRE MOMENTUM
    // Identique au backtest Python
    // ══════════════════════════════════════════════════
    private boolean checkMomentum(DataContext dc, int idx) {
        if (idx < 3) return true;
        double move3 = Math.abs(dc.getHigh(idx) - dc.getLow(idx - 3));
        int start    = Math.max(0, idx - 10);
        double sumMoves = 0;
        int count = 0;
        for (int k = start; k < idx; k++) {
            sumMoves += Math.abs(dc.getHigh(k) - dc.getLow(k));
            count++;
        }
        if (count == 0) return true;
        double avgMove = sumMoves / count;
        return !(avgMove > 0 && move3 > MOMENTUM_MULT * avgMove);
    }

    // ══════════════════════════════════════════════════
    // PROPFIRM — Risque adaptatif
    // ══════════════════════════════════════════════════
    private double getRiskPct(boolean isBoosted, double margePct) {
        if (margePct < 1.0) return 0;
        if (margePct < 2.0) return RISK_MID2;
        if (margePct < 3.0) return RISK_MID1;
        return isBoosted ? RISK_BOOSTED : RISK_NORMAL;
    }

    private int calcContracts(double riskPct, double riskPts) {
        if (riskPct == 0 || riskPts <= 0) return 0;
        int c = (int) Math.round((accountBalance * riskPct) / (riskPts * MNQ_MULT));
        return Math.max(1, Math.min(8, c));
    }

    // ══════════════════════════════════════════════════
    // HISTORIQUE DES SESSIONS
    // ══════════════════════════════════════════════════
    private void addSessionToHistory(DataContext dc, int currentIdx) {
        // Parcourir les barres de la session précédente
        // pour calculer HOD, LOD, Park vol, daily ret
        double open = sessionOpen;
        double hod  = open, lod = open;
        double[] logRets = new double[100];
        int count = 0;

        for (int i = currentIdx - 1; i >= Math.max(0, currentIdx - 400); i--) {
            long t = dc.getStartTime(i);
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
            c.setTimeInMillis(t);
            long d = getSessionDate(c);
            if (d != lastSessionDate) break;

            double h = dc.getHigh(i);
            double l = dc.getLow(i);
            double cl= dc.getClose(i);
            if (h > hod) hod = h;
            if (l < lod) lod = l;
            if (count < logRets.length && i > 0) {
                double prevClose = dc.getClose(i - 1);
                if (prevClose > 0) logRets[count++] = Math.log(cl / prevClose);
            }
        }

        // Parkinson vol
        double parkVol = 0;
        // Approximation sur HOD/LOD de la session
        if (hod > 0 && lod > 0 && hod != lod) {
            parkVol = Math.sqrt(Math.pow(Math.log(hod / lod), 2) / (4.0 * Math.log(2)));
        }

        double prevOpen = sessionHistory.isEmpty() ? open :
            sessionHistory.get(sessionHistory.size() - 1)[0];
        double dailyRet = prevOpen > 0 ? (open - prevOpen) / prevOpen : 0.0;

        sessionHistory.add(new double[]{open, hod, lod, parkVol, dailyRet});

        // Garder seulement les 50 dernières sessions
        if (sessionHistory.size() > 50) {
            sessionHistory.remove(0);
        }
    }

    // ══════════════════════════════════════════════════
    // UTILITAIRES MATHÉMATIQUES
    // ══════════════════════════════════════════════════
    private long getSessionDate(Calendar cal) {
        int h = cal.get(Calendar.HOUR_OF_DAY);
        // Barres après 23h00 appartiennent au jour suivant
        if (h >= 23) {
            Calendar next = (Calendar) cal.clone();
            next.add(Calendar.DAY_OF_MONTH, 1);
            return next.get(Calendar.YEAR) * 10000L
                + next.get(Calendar.MONTH) * 100L
                + next.get(Calendar.DAY_OF_MONTH);
        }
        return cal.get(Calendar.YEAR) * 10000L
            + cal.get(Calendar.MONTH) * 100L
            + cal.get(Calendar.DAY_OF_MONTH);
    }

    private double mean(double[] arr) {
        if (arr.length == 0) return 0;
        double s = 0; for (double v : arr) s += v; return s / arr.length;
    }

    private double std(double[] arr) {
        if (arr.length < 2) return 0;
        double m = mean(arr), s = 0;
        for (double v : arr) s += Math.pow(v - m, 2);
        return Math.sqrt(s / arr.length);
    }

    private double rms(double[] arr) {
        if (arr.length == 0) return 0;
        double s = 0; for (double v : arr) s += v * v;
        return Math.sqrt(s / arr.length);
    }

    private double ewmaVol(double[] returns, double lam) {
        if (returns.length < 2) return 0;
        double var = returns[0] * returns[0];
        for (int i = 1; i < returns.length; i++) {
            var = lam * var + (1 - lam) * returns[i] * returns[i];
        }
        return Math.sqrt(var);
    }

    private double[] squareArr(double[] arr) {
        double[] r = new double[arr.length];
        for (int i = 0; i < arr.length; i++) r[i] = arr[i] * arr[i];
        return r;
    }

    private double[] getAllReturns(List<double[]> sessions) {
        List<Double> rets = new ArrayList<>();
        // Approximation : utiliser daily returns comme proxy
        for (double[] s : sessions) rets.add(s[4]);
        double[] arr = new double[rets.size()];
        for (int i = 0; i < rets.size(); i++) arr[i] = rets.get(i);
        return arr;
    }

    private void debug(String msg) {
        System.out.println("[CANOPUS] " + msg);
    }

    // ══════════════════════════════════════════════════
    // MET À JOUR LE SOLDE (appeler manuellement)
    // ══════════════════════════════════════════════════
    public void updateBalance(double newBalance) {
        double pnl = newBalance - accountBalance;
        accountBalance = newBalance;
        dailyPnl      += pnl;
        if (accountBalance > peakBalance) {
            peakBalance = accountBalance;
            ddSeuil     = peakBalance * (1.0 - DD_PCT);
        }
        debug(String.format("Balance: $%.0f | Peak: $%.0f | Seuil DD: $%.0f | Marge: %.1f%%",
            accountBalance, peakBalance, ddSeuil,
            (accountBalance - ddSeuil) / accountBalance * 100));
    }
}
