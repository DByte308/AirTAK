Steady-state dominates the budget. In a 100-node mesh at Profile 0 (310ms ToA), your device relays ~2.8 unique packets/second just from position and player-state traffic. That's ~870 relay transmissions/hour consuming ~270 seconds of airtime — already 7.5× over the EU sub-band g 1% limit (36s/hour) even before our changes.

Our additions are one-shot, not sustained:

Event	Packets	Airtime (Profile 0)	% of hourly budget
Sync response (10 markers + 25 messages)	35	~10.9s	0.3%
Overlay alignment share	1	~0.31s	0.009%
Marker snapshot response (10 markers)	10	~3.1s	0.09%
Even if 5 players join at staggered times and trigger separate sync responses, that's 5 × 0.3% = 1.5% of the hourly budget. The existing relay traffic from 100 nodes doing smart beaconing at 90s intervals already pushes 7.5× over sub-band g (1%) and 75% of sub-band g1 (10%).