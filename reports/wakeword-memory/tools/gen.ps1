param([string]$OutDir)
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice('Microsoft Heami Desktop')
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
function Say($text, $rate, $pitch, $file) {
  $s.SetOutputToWaveFile($file, $fmt)
  $ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ko-KR'><break time='400ms'/><prosody rate='$rate' pitch='$pitch'>$text</prosody><break time='700ms'/></speak>"
  $s.SpeakSsml($ssml)
  $s.SetOutputToNull()
}
New-Item -ItemType Directory -Force "$OutDir\pos","$OutDir\neg" | Out-Null
$rates = @('x-slow','slow','medium','fast','x-fast'); $pitches = @('x-low','low','medium','high','x-high')
$i=0; foreach ($r in $rates) { foreach ($p in $pitches) { $i++; Say '렌디' $r $p "$OutDir\pos\pos_$i`_$r`_$p.wav" } }
$neg = @('렌트','렌즈','엔디','랜드','렌터카','멘디','센터','레디','렌디션','젠틀','라디오 켜','에어컨 꺼줘','라이트 켜줘','네, 말씀하세요','오늘 날씨 어때','비상등 어디 있어','그냥 해 봐','안녕하세요','엔진 켜','브레이크','이렇게 하면 돼','렌지','펜디','벤치','헨리','레몬','델리','데니','캔디','핸디')
$j=0; foreach ($t in $neg) { foreach ($r in @('medium','fast')) { $j++; Say $t $r 'medium' "$OutDir\neg\neg_$j.wav" } }
"pos=$i neg=$j"
