function ConvertTo-Graphviz ($nodes, $edges) {
	function attrToDef ($attr) {
		switch ($_.name) {
			'label' {"$($attr.name)=""$($attr.value)"""}
			'color' { $attr.name+'='+$attr.value }
			default { $attr.name+'='+$attr.value }
		}
	}
	
	$nodeDefs = $nodes | % {
		$attrsDef = $_.psobject.properties | ? name -ne id | select name,value | % {
			attrToDef $_
		} | Join-String -Separator ' '
		
		if ($attrsDef) {$attrsDef = "[$attrsDef]"}
		
		$_.id,$attrsDef -join ' '
	}
	
	
	$edgeDefs = $edges | % { 
		$attrsDef = $_.psobject.properties | ? name -notin from,to | select name,value | % {
			attrToDef $_
		} | Join-String -Separator ' '

		if ($attrsDef) {$attrsDef = "[$attrsDef]"}

		"{0} -> {1} {2}" -f $_.from,$_.to,$attrsDef
	}
	return @()+"digraph G {"+$nodeDefs+$edgeDefs+"}"
	
	
}

# DOWNLOAD data
# https://dataverse.pushdom.ru/dataset.xhtml?persistentId=doi:10.31860/openlit-2022.1-C005
$persistentId = 'doi:10.31860/openlit-2022.1-C005'
$outFile = "$($persistentId -replace '[^\w]').zip"
$url = "https://dataverse.pushdom.ru/api/access/dataset/:persistentId/?persistentId=$persistentId&format=original"
Invoke-RestMethod $url -OutFile $outFile
Expand-Archive $outFile .

# GET GRAPH

[xml]$xml = cat .\War_and_Peace.xml | % {$_.trim()} | Join-String -Separator ''
$nametable = new-object System.Xml.NameTable;
$nsmgr = new-object System.Xml.XmlNamespaceManager($nametable);
$nsmgr.AddNamespace("x", "http://www.tei-c.org/ns/1.0");

$edgesTsv = $xml.SelectNodes("//x:said",$nsmgr) | ? {$_.who -and $_.corresp -and $_.speech_id } | % {
	$_.who,$_.speech_id -join "`t"
	$_.speech_id,$_.corresp -join "`t"
} | ? {$_ -notmatch ';'} | % {
	$_.trim() -replace '\.|-|''|\)|\(' -replace ' ','_'
} | select -Unique

$nodesTsv = $edgesTsv -split "`t" | select -Unique | % {
	$group = $_ -match '^\d' ? 'Phrase' : 'Person'
	$label = $group -eq 'Person' ? $_ : ''
	$_,$label,$group -join "`t"
} | select -Unique

$edges = $edgesTsv | ConvertFrom-Csv -delim "`t" -Header from,to
$nodes = $nodesTsv | ConvertFrom-Csv -delim "`t" -Header id,label,group


# SAVE GRAPH

$gv = ConvertTo-Graphviz $nodes $edges
$gv | Set-Content warAndPeace.dot


# USE GEPHI TOOLKIT
# Download jar here: https://gephi.org/toolkit/
$gephiToolkit = 'C:\Users\user\downloads\gephi-toolkit-0.10.0-all.jar'
$gephiStarter = 'GephiStarter.java'

# nice
$json = @{
	InFile='warAndPeace.dot';
	filters=@(@{name='GiantComponent'});
	layouts=@(@{name='ForceAtlas2'; steps=200; scaling=3},@{name='YifanHuProportional'; steps=10});
	partitionNodesBy='group';
	rankNodesByDegree=@{minSize=5; maxSize=50};
	preview=@{edgeColor='source'};
	OutFile = 'gephi.pdf'
} | ConvertTo-Json -d 9
$json | java -classpath $gephiToolkit $gephiStarter
start .\gephi.pdf

return 
# USE GEPHI GUI

$gephiBin = "C:\Program Files\Gephi-0.10.1\bin\gephi64.exe"
& $gephiBin .\warAndPeace.dot
