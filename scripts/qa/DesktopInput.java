import java.awt.*;
import java.awt.event.*;
import java.io.*;
import javax.imageio.ImageIO;
/** clientと別JVMでX11へ入力し、描画やゲームクラスには介入しない。 */
public final class DesktopInput {
 public static void main(String[] args)throws Exception {
  Robot r=new Robot();r.setAutoDelay(80);
  if(args[0].equals("click")){r.mouseMove(Integer.parseInt(args[1]),Integer.parseInt(args[2]));r.mousePress(InputEvent.BUTTON1_DOWN_MASK);r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);}
  if(args[0].equals("move"))r.mouseMove(Integer.parseInt(args[1]),Integer.parseInt(args[2]));
  if(args[0].equals("key")){int k=Integer.parseInt(args[1]);r.keyPress(k);r.keyRelease(k);}
  if(args[0].equals("type"))for(char c:args[1].toCharArray()){int k=KeyEvent.getExtendedKeyCodeForChar(c);boolean shift=Character.isUpperCase(c)||"@:_".indexOf(c)>=0;if(c=='@')k=KeyEvent.VK_2;if(c==':')k=KeyEvent.VK_SEMICOLON;if(c=='_')k=KeyEvent.VK_MINUS;if(shift)r.keyPress(KeyEvent.VK_SHIFT);r.keyPress(k);r.keyRelease(k);if(shift)r.keyRelease(KeyEvent.VK_SHIFT);}
  if(args[0].equals("screen"))ImageIO.write(r.createScreenCapture(new Rectangle(0,0,1280,960)),"png",new File(args[1]));
 }
}
