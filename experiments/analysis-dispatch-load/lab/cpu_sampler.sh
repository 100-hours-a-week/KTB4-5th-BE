#!/bin/sh
# 2초마다 전체 CPU 사용률과 프로세스별(MySQL·BE·가짜 AI·부하 발생기) CPU를 남긴다.
out=$1
while true; do
  t=$(date +%s)
  idle1=$(awk '/^cpu /{print $5+$6; exit}' /proc/stat); total1=$(awk '/^cpu /{s=0; for(i=2;i<=NF;i++) s+=$i; print s; exit}' /proc/stat)
  sleep 2
  idle2=$(awk '/^cpu /{print $5+$6; exit}' /proc/stat); total2=$(awk '/^cpu /{s=0; for(i=2;i<=NF;i++) s+=$i; print s; exit}' /proc/stat)
  busy=$(awk -v i1=$idle1 -v i2=$idle2 -v t1=$total1 -v t2=$total2 'BEGIN{printf "%.1f", 100*(1-(i2-i1)/(t2-t1))}')
  procs=$(top -b -n1 -w 200 | awk 'NR>7 && ($12 ~ /mysqld|java|python/) {a[$12]+=$9} END {for (k in a) printf "%s=%s ", k, a[k]}')
  echo "$t busy=$busy $procs" >> $out
done
