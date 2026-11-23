export type Sheet={name:string;columns:string[];rows:(string|number|null)[][]}
export async function saveBook(filename:string,sheets:Sheet[]) {
  const x=await import('xlsx'),book=x.utils.book_new()
  for(const sheet of sheets){
    const data=x.utils.aoa_to_sheet([sheet.columns,...sheet.rows])
    data['!cols']=sheet.columns.map((s,i)=>({wch:Math.min(45,Math.max(12,s.length*2,...sheet.rows.slice(0,60).map(r=>String(r[i]??'').length+2)))}))
    x.utils.book_append_sheet(book,data,sheet.name)
  }
  x.writeFile(book,filename+'.xlsx')
}
export async function readBook(file:File){
  if(file.size>10*1024*1024)throw Error('文件超过 10 MB')
  const x=await import('xlsx')
  const book=x.read(await file.arrayBuffer(),{type:'array',sheetRows:10002,raw:true})
  if(!book.SheetNames.length)throw Error('工作簿缺少工作表')
  const grid=x.utils.sheet_to_json<unknown[]>(book.Sheets[book.SheetNames[0]],{header:1,defval:'',blankrows:false})
  if(grid.length>10001)throw Error('单次导入最多 10000 行')
  return grid.map(row=>row.map(v=>String(v??'').trim()))
}
