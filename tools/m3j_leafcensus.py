import re, collections, sys
TL=[l.split() for l in open('machine/raw/M3J-m3j-f1.timeline')]
launch=int([p for p in TL if p[0]=='LAUNCH'][0][1])
lclock=[p for p in TL if p[0]=='LAUNCH'][0][2]
lsec=sum(int(x)*m for x,m in zip(lclock.split(':'),[3600,60,1]))
def wclk(c): return launch+sum(int(x)*m for x,m in zip(c.split(':'),[3600,60,1]))-lsec
bounds=[(p[0],wclk(p[1])) for p in TL if len(p)==2]
def phase(w):
    cur='BOOT'
    for n_,c in bounds:
        if w>=c: cur=n_
    return {'BOOT_DONE':'SETTLE','SETTLE_END':'BOTJOIN','BOT_READY':'BUILD','BUILD_START':'BUILD','BUILD_END':'F','FACTORY_END':'E','ENTITY_END':'R','RECOVERY_END':'T','STOP':'S'}.get(cur,cur)

for TARGET in sys.argv[1:]:
    roots=collections.Counter(); mids=collections.Counter(); N=[0]
    cur=[None]; frames=[[]]; fs=[None]
    def flush():
        if not frames[0]: return
        if TARGET in frames[0][0]:
            ph=phase(cur[0])
            if ph in ('F','E'):
                N[0]+=1
                roots[frames[0][-1]]+=1
                for f in frames[0][1:7]: mids[f]+=1
    for line in open('machine/raw/M3J-print-combined.txt',encoding='utf-8',errors='replace'):
        line=line.rstrip('\r\n')
        m=re.match(r'^\s*startTime = (\d+):(\d+):([\d.]+)$',line)
        if m:
            flush(); frames[0]=[]
            rel=int(m.group(1))*3600+int(m.group(2))*60+float(m.group(3))
            if fs[0] is None: fs[0]=rel
            cur[0]=launch+rel-fs[0]
            continue
        if line.startswith('jdk.'):
            flush()
            frames[0]=[] if line.startswith('jdk.ExecutionSample') else None
            continue
        if frames[0] is not None:
            mm=re.match(r'^\s{4,}(\S+)\(.*$',line)
            if mm: frames[0].append(mm.group(1))
    flush()
    print('==',TARGET,'F/E samples:',N[0])
    print('  roots:',roots.most_common(3))
    for f,c in mids.most_common(5): print('   ',c,f)
