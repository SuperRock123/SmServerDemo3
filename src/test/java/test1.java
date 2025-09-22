import cn.zmvision.ccm.smserver.service.DataService;
import org.junit.jupiter.api.Test;
import org.toehold.ToeholdServerImp;

public class test1 {
    DataService service=new ToeholdServerImp() ;

    @Test
    public void test1() {
        System.out.println("test1");
        boolean r=service.check_allow_sn("1");
    }
    @Test
    public void test2() {
        System.out.println("test2");

    }
}
