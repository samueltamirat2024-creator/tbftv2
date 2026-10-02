{{/* n = 3f+1 or the chart will not render. A 5- or 6-replica "BFT" cluster tolerates the same
     f as a 4-replica one while costing more and looking safer, which is the dangerous part. */}}
{{- define "vdr.validateN" -}}
{{- $n := int .Values.replicaCount -}}
{{- if ne (mod (sub $n 1) 3) 0 -}}
{{- fail (printf "replicaCount must be 3f+1 (4, 7, 10, ...); got %d" $n) -}}
{{- end -}}
{{- end -}}
