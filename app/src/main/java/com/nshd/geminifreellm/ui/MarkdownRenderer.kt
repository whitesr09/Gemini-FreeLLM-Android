package com.nshd.geminifreellm.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal sealed interface MarkdownBlock{
    data class Paragraph(val text:String):MarkdownBlock
    data class Heading(val level:Int,val text:String):MarkdownBlock
    data class Bullet(val depth:Int,val text:String):MarkdownBlock
    data class Numbered(val depth:Int,val number:String,val text:String):MarkdownBlock
    data class Quote(val text:String):MarkdownBlock
    data object Rule:MarkdownBlock
    data class Code(val language:String,val code:String):MarkdownBlock
    data class Table(val headers:List<String>,val rows:List<List<String>>):MarkdownBlock
}
internal object MarkdownParser{
    fun parse(source:String):List<MarkdownBlock>{
        val lines=source.replace("\r\n","\n").replace('\r','\n').split('\n');val fence="\u0060\u0060\u0060";val out=mutableListOf<MarkdownBlock>();var i=0
        while(i<lines.size){
            val line=lines[i];val t=line.trim()
            if(t.isBlank()){i++;continue}
            if(t.startsWith(fence)){val lang=t.removePrefix(fence).trim().take(20);i++;val code=buildString{while(i<lines.size&&!lines[i].trim().startsWith(fence)){append(lines[i]);if(i+1<lines.size&&!lines[i+1].trim().startsWith(fence))append('\n');i++}};if(i<lines.size)i++;out+=MarkdownBlock.Code(lang,code);continue}
            Regex("^(#{1,6})\\s+(.+)$").matchEntire(t)?.let{out+=MarkdownBlock.Heading(it.groupValues[1].length,it.groupValues[2]);i++;continue}
            if(t=="---"||t=="***"||t=="___"){out+=MarkdownBlock.Rule;i++;continue}
            if(t.startsWith(">")){out+=MarkdownBlock.Quote(t.removePrefix(">").trim());i++;continue}
            if(i+1<lines.size&&t.contains('|')){
                val h=t.split('|').map{it.trim()}.let{it.dropWhile(String::isEmpty).dropLastWhile(String::isEmpty)};val s=lines[i+1].trim().split('|').map{it.trim()}.let{it.dropWhile(String::isEmpty).dropLastWhile(String::isEmpty)}
                if(h.size>=2&&s.size==h.size&&s.all{it.matches(Regex("^:?-{3,}:?$"))}){val rows=mutableListOf<List<String>>();i+=2;while(i<lines.size&&lines[i].contains('|')&&lines[i].trim().isNotBlank()){rows+=lines[i].trim().split('|').map{it.trim()}.let{it.dropWhile(String::isEmpty).dropLastWhile(String::isEmpty)};i++};out+=MarkdownBlock.Table(h,rows);continue}
            }
            Regex("^(\\s*)[-*+]\\s+(.+)$").matchEntire(line)?.let{out+=MarkdownBlock.Bullet(it.groupValues[1].length/2,it.groupValues[2]);i++;continue}
            Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$").matchEntire(line)?.let{out+=MarkdownBlock.Numbered(it.groupValues[1].length/2,it.groupValues[2],it.groupValues[3]);i++;continue}
            val p=buildString{append(t);i++;while(i<lines.size&&lines[i].trim().isNotBlank()){val n=lines[i].trim();if(n.startsWith(fence)||n.startsWith("#")||n.startsWith(">")||Regex("^[-*+]\\s+").containsMatchIn(n)||Regex("^\\d+[.)]\\s+").containsMatchIn(n))break;append('\n').append(n);i++}};out+=MarkdownBlock.Paragraph(p)
        }
        return out
    }
}
@Composable internal fun MarkdownRenderer(markdown:String,onCopyCode:(String)->Unit,onShareCode:(String)->Unit,isError:Boolean=false){
    val blocks=remember(markdown){MarkdownParser.parse(markdown)};val c=LocalAppColors.current
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){blocks.forEach{b->when(b){
        is MarkdownBlock.Heading->BasicText(inlineMarkdown(b.text,c.text,c.elevated,c.accent),color=c.text,fontSize=(25-b.level*2).coerceAtLeast(14).sp,fontWeight=FontWeight.SemiBold)
        is MarkdownBlock.Paragraph->BasicText(inlineMarkdown(b.text,if(isError)c.error else c.text,c.elevated,c.accent),color=if(isError)c.error else c.text,fontSize=15.sp,lineHeight=23.sp)
        is MarkdownBlock.Bullet->BasicText(inlineMarkdown("  ".repeat(b.depth)+"• "+b.text,c.text,c.elevated,c.accent),color=c.text,fontSize=15.sp,lineHeight=22.sp)
        is MarkdownBlock.Numbered->BasicText(inlineMarkdown("  ".repeat(b.depth)+b.number+". "+b.text,c.text,c.elevated,c.accent),color=c.text,fontSize=15.sp,lineHeight=22.sp)
        is MarkdownBlock.Quote->BasicText(inlineMarkdown("│ "+b.text,c.muted,c.elevated,c.accent),color=c.muted,fontSize=14.sp,lineHeight=21.sp,modifier=Modifier.fillMaxWidth().background(c.elevated,RoundedCornerShape(8.dp)).padding(10.dp))
        MarkdownBlock.Rule->Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        is MarkdownBlock.Code->CodeBlock(b,onCopyCode,onShareCode)
        is MarkdownBlock.Table->MarkdownTable(b)
    }}}
}
@Composable private fun CodeBlock(block:MarkdownBlock.Code,onCopy:(String)->Unit,onShare:(String)->Unit){
    val c=LocalAppColors.current;val lines=block.code.split('\n')
    Column(Modifier.fillMaxWidth().background(c.surface,RoundedCornerShape(10.dp)).border(1.dp,c.border,RoundedCornerShape(10.dp))){
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp)){BasicText(block.language.ifBlank{"code"},color=c.muted,fontSize=10.sp,modifier=Modifier.weight(1f));ActionText("Copy"){onCopy(block.code)};ActionText("Share"){onShare(block.code)}}
        SelectionContainer{Column(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)){lines.forEachIndexed{idx,line->Row{if(lines.size>=20)BasicText((idx+1).toString().padStart(3,' ')+" ",color=c.muted,fontSize=11.sp,fontFamily=FontFamily.Monospace);BasicText(syntaxHighlight(line),color=c.text,fontSize=11.sp,fontFamily=FontFamily.Monospace,lineHeight=17.sp)}}}}
    }
}
@Composable private fun MarkdownTable(block:MarkdownBlock.Table){val c=LocalAppColors.current;Row(Modifier.horizontalScroll(rememberScrollState())){Column(Modifier.border(1.dp,c.border,RoundedCornerShape(8.dp))){Row{block.headers.forEach{BasicText(inlineMarkdown(it,c.text,c.elevated,c.accent),color=c.text,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.widthIn(min=90.dp).padding(8.dp))}};block.rows.forEach{row->Row{row.forEach{BasicText(inlineMarkdown(it,c.muted,c.elevated,c.accent),color=c.muted,fontSize=11.sp,modifier=Modifier.widthIn(min=90.dp).border(.5.dp,c.border).padding(8.dp))}}}}}}
@Composable private fun inlineMarkdown(text:String,defaultColor:Color,codeBackground:Color,accent:Color):AnnotatedString{
    val b=AnnotatedString.Builder();val regex=Regex("(\\*\\*[^*]+\\*\\*|__[^_]+__|~~[^~]+~~|\u0060[^\u0060]+\u0060|\\[[^]]+\\]\\([^)]+\\))");var pos=0
    regex.findAll(text).forEach{m->{if(m.range.first>pos)b.append(text.substring(pos,m.range.first));val token=m.value;when{token.startsWith("**")||token.startsWith("__")->b.withStyle(SpanStyle(fontWeight=FontWeight.Bold)){append(token.drop(2).dropLast(2))}
        token.startsWith("~~")->b.withStyle(SpanStyle(textDecoration=TextDecoration.LineThrough)){append(token.drop(2).dropLast(2))}
        token.firstOrNull()=='\u0060'->b.withStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=codeBackground)){append(token.drop(1).dropLast(1))}
        token.startsWith("[")->b.withStyle(SpanStyle(color=accent,textDecoration=TextDecoration.Underline)){append(token.substringBefore("](").removePrefix("["))}
        else->b.withStyle(SpanStyle(fontStyle=FontStyle.Italic,color=defaultColor)){append(token.drop(1).dropLast(1))}};pos=m.range.last+1};if(pos<text.length)b.append(text.substring(pos));return b.toAnnotatedString()
}
private fun syntaxHighlight(line:String):AnnotatedString{val b=AnnotatedString.Builder();val regex=Regex("\\b(fun|val|var|class|object|if|else|when|for|while|return|import|package|const|true|false|null|public|private|suspend|override|interface|data)\\b");var pos=0;regex.findAll(line).forEach{m->{if(m.range.first>pos)b.append(line.substring(pos,m.range.first));b.withStyle(SpanStyle(fontWeight=FontWeight.Bold)){append(m.value)};pos=m.range.last+1}};if(pos<line.length)b.append(line.substring(pos));return b.toAnnotatedString()}
