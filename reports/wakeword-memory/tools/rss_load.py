import sys, psutil, time
from vosk import Model, SetLogLevel
SetLogLevel(-1); p = psutil.Process(); r0 = p.memory_info().rss; t = time.time(); m = Model(sys.argv[1])
print(f'{sys.argv[1].split("/")[-1]} load={time.time()-t:.2f}s rss_delta={(p.memory_info().rss-r0)/2**20:.0f}MB')
